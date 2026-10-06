package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizeResult;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.normalize.Normalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多设备并发采集调度。
 *
 * <p>每个设备拥有独立的单线程调度器：任何一个设备轮询异常（甚至 Error 级故障）都在
 * 该设备自己的任务内被捕获并回调 {@link CollectionListener#onDeviceError}，
 * 不会传播到调度线程或其它设备。
 *
 * <p>本类只维护在线状态与时间戳类运行时信息（{@link CollectorStatus}）；
 * 接受/拒绝/轮询等计数由 {@link CollectionListener} 的实现方（网关）统一记入
 * StatsRegistry——一条读数最终算接受还是算拒绝由网关在乱序检查与缓冲写入后
 * 唯一决定，采集层不另存一份可能与之分叉的计数。
 */
public final class CollectorSupervisor {

    private final List<SimulatedDevice> devices;
    private final Normalizer normalizer;
    private final CollectionListener listener;
    private final long intervalMillis;
    private final Map<String, DeviceRuntime> runtimes = new LinkedHashMap<>();
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    public CollectorSupervisor(List<SimulatedDevice> devices,
                               Normalizer normalizer,
                               Map<String, PointDefinition> catalog,
                               CollectionListener listener,
                               long intervalMillis) {
        this.devices = List.copyOf(devices);
        this.normalizer = normalizer;
        this.listener = listener;
        this.intervalMillis = intervalMillis;
        for (SimulatedDevice d : devices) {
            runtimes.put(d.deviceId(), new DeviceRuntime(d.deviceId(),
                    d.online() ? DeviceStatus.UP : DeviceStatus.DOWN));
        }
    }

    public void start() {
        for (SimulatedDevice device : devices) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "collector-" + device.deviceId());
                t.setDaemon(true);
                return t;
            });
            executors.add(executor);
            ScheduledFuture<?> ignored = executor.scheduleWithFixedDelay(
                    () -> safePoll(device), 0L, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        for (ScheduledExecutorService executor : executors) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executors.clear();
    }

    private void safePoll(SimulatedDevice device) {
        DeviceRuntime rt = runtimes.get(device.deviceId());
        rt.lastPollAt = System.currentTimeMillis();
        List<RawReading> readings;
        try {
            readings = device.poll();
        } catch (Throwable t) {
            // 捕获 Throwable：单设备的任何故障都不得影响调度器和其它设备
            rt.lastErrorAt = System.currentTimeMillis();
            rt.lastError = String.valueOf(t);
            listener.onDeviceError(device.deviceId(), t);
            updateOnline(rt, false);
            return;
        }
        // 真实轮询成功（空批次也算）：健康判定只认这个结果
        listener.onPollSucceeded(device.deviceId());
        updateOnline(rt, true);
        for (RawReading raw : readings) {
            NormalizeResult result;
            try {
                result = normalizer.normalize(raw);
            } catch (Throwable t) {
                // 归一化内部不应抛出；万一抛出，计为内部错误而不是设备故障，
                // 不影响该设备的健康判定
                rt.lastErrorAt = System.currentTimeMillis();
                rt.lastError = String.valueOf(t);
                listener.onInternalError(device.deviceId(), raw, t);
                continue;
            }
            // 接受/拒绝计数不在此记录：归一化通过后网关还要做乱序检查与缓冲写入，
            // 最终口径由网关统一计入 StatsRegistry（见 CollectionListener 回调）
            if (result.accepted()) {
                listener.onAccepted(device.deviceId(), raw, result.record());
            } else {
                listener.onRejected(device.deviceId(), result.rejected());
            }
        }
    }

    private void updateOnline(DeviceRuntime rt, boolean online) {
        DeviceStatus next = online ? DeviceStatus.UP : DeviceStatus.DOWN;
        if (rt.status != next) {
            rt.status = next;
            listener.onOnlineState(rt.deviceId, online);
        }
    }

    /** 采集运行时快照（只含非计数信息；计数口径以 StatsRegistry 为准）。 */
    public Map<String, CollectorStatus> snapshot() {
        Map<String, CollectorStatus> out = new LinkedHashMap<>();
        runtimes.forEach((id, rt) -> out.put(id, new CollectorStatus(
                id, rt.status, rt.lastPollAt, rt.lastErrorAt, rt.lastError)));
        return out;
    }

    /** 单设备运行时信息（可变，仅被该设备自己的采集线程写，读线程看到快照一致值）。 */
    private static final class DeviceRuntime {
        final String deviceId;
        volatile DeviceStatus status;
        volatile Long lastPollAt = null;
        volatile Long lastErrorAt = null;
        volatile String lastError = null;

        DeviceRuntime(String deviceId, DeviceStatus initial) {
            this.deviceId = deviceId;
            this.status = initial;
        }
    }

    /** 保留设备异常类型引用以便上层按类型区分日志。 */
    @SuppressWarnings("unused")
    private static final Class<?> DEVICE_UNAVAILABLE = ScriptedDevice.DeviceUnavailableException.class;
}

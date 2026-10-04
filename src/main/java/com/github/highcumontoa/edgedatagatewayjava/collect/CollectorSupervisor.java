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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多设备并发采集调度。
 *
 * <p>每个设备拥有独立的单线程调度器：任何一个设备轮询异常（甚至 Error 级故障）都在
 * 该设备自己的任务内被捕获并回调 {@link CollectionListener#onDeviceError}，
 * 不会传播到调度线程或其它设备。
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
        rt.pollCount.incrementAndGet();
        rt.lastPollAt = System.currentTimeMillis();
        listener.onPolled(device.deviceId());
        List<RawReading> readings;
        try {
            readings = device.poll();
        } catch (Throwable t) {
            // 捕获 Throwable：单设备的任何故障都不得影响调度器和其它设备
            rt.errorCount.incrementAndGet();
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
                rt.errorCount.incrementAndGet();
                rt.lastErrorAt = System.currentTimeMillis();
                rt.lastError = String.valueOf(t);
                listener.onInternalError(device.deviceId(), raw, t);
                continue;
            }
            if (result.accepted()) {
                rt.acceptedCount.incrementAndGet();
                listener.onAccepted(device.deviceId(), raw, result.record());
            } else {
                rt.rejectedCount.incrementAndGet();
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

    public Map<String, DeviceStats> snapshot() {
        Map<String, DeviceStats> out = new LinkedHashMap<>();
        runtimes.forEach((id, rt) -> out.put(id, new DeviceStats(
                id, rt.status,
                rt.pollCount.get(), rt.errorCount.get(),
                rt.acceptedCount.get(), rt.rejectedCount.get(),
                rt.lastPollAt, rt.lastErrorAt, rt.lastError)));
        return out;
    }

    /** 单设备运行时统计（可变，仅被该设备自己的采集线程写，读线程看到快照一致值）。 */
    private static final class DeviceRuntime {
        final String deviceId;
        volatile DeviceStatus status;
        final AtomicLong pollCount = new AtomicLong();
        final AtomicLong errorCount = new AtomicLong();
        final AtomicLong acceptedCount = new AtomicLong();
        final AtomicLong rejectedCount = new AtomicLong();
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

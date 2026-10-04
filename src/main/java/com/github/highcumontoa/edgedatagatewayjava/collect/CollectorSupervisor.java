package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多设备并发采集调度。
 *
 * <p>每个设备拥有独立的单线程调度器：任何一个设备轮询异常（甚至 Error 级故障）都在
 * 该设备自己的任务内被捕获并回调 {@link CollectionListener#onPollFailure}，
 * 不会传播到调度线程或其它设备。健康判定、归一化与统计计数全部在
 * {@link CollectionListener}（网关）一侧、于网关一致性写锁内完成。
 */
public final class CollectorSupervisor {

    private final List<SimulatedDevice> devices;
    private final CollectionListener listener;
    private final long intervalMillis;
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    public CollectorSupervisor(List<SimulatedDevice> devices,
                               CollectionListener listener,
                               long intervalMillis) {
        this.devices = List.copyOf(devices);
        this.listener = listener;
        this.intervalMillis = intervalMillis;
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
        String deviceId = device.deviceId();
        listener.onPollBegin(deviceId, System.currentTimeMillis());
        List<RawReading> readings;
        try {
            readings = device.poll();
        } catch (Throwable t) {
            // 捕获 Throwable：单设备的任何故障都不得影响调度器和其它设备；
            // 这是本设备真实的失败，参与健康降级判定（与上行通道无关）。
            listener.onPollFailure(deviceId, t, System.currentTimeMillis());
            return;
        }
        listener.onPollSuccess(deviceId, readings, System.currentTimeMillis());
    }
}

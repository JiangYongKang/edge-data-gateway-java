package com.github.highcumontoa.edgedatagatewayjava.device;

import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 按预置脚本逐批产出读数的模拟设备。
 *
 * <p>脚本是若干个批次供应器，每个供应器返回一批读数，也可以抛异常模拟设备故障；
 * {@code loop=true} 时脚本循环执行，{@code false} 时耗尽后返回空批次。
 * {@link #setOnline(boolean)} 模拟设备掉线/恢复，掉线时 {@link #poll()} 抛
 * {@link DeviceUnavailableException}。
 */
public final class ScriptedDevice implements SimulatedDevice {

    /** 设备不可用（掉线）异常。 */
    public static final class DeviceUnavailableException extends RuntimeException {
        public DeviceUnavailableException(String deviceId) {
            super("device unavailable: " + deviceId);
        }
    }

    private final String deviceId;
    private final List<PointDefinition> points;
    private final List<Supplier<List<RawReading>>> batches;
    private final boolean loop;
    private final AtomicInteger index = new AtomicInteger(0);
    private volatile boolean online;

    public ScriptedDevice(String deviceId,
                          List<PointDefinition> points,
                          List<? extends Supplier<List<RawReading>>> batches,
                          boolean initiallyOnline) {
        this(deviceId, points, batches, initiallyOnline, true);
    }

    public ScriptedDevice(String deviceId,
                          List<PointDefinition> points,
                          List<? extends Supplier<List<RawReading>>> batches,
                          boolean initiallyOnline,
                          boolean loop) {
        this.deviceId = deviceId;
        this.points = List.copyOf(points);
        this.batches = List.copyOf(batches);
        this.online = initiallyOnline;
        this.loop = loop;
    }

    @Override
    public String deviceId() {
        return deviceId;
    }

    @Override
    public List<PointDefinition> points() {
        return points;
    }

    @Override
    public List<RawReading> poll() {
        if (!online) {
            throw new DeviceUnavailableException(deviceId);
        }
        // 每个设备只有一个采集线程调用 poll，无需额外同步
        int i = index.getAndIncrement();
        if (i >= batches.size()) {
            if (!loop) {
                return new ArrayList<>();
            }
            i %= batches.size();
        }
        Supplier<List<RawReading>> supplier = batches.get(i);
        List<RawReading> got = supplier.get();
        return got == null ? new ArrayList<>() : new ArrayList<>(got);
    }

    @Override
    public boolean online() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
        if (online) {
            // 恢复后从头继续，保证测试中恢复事件确定可观察
            index.set(0);
        }
    }
}

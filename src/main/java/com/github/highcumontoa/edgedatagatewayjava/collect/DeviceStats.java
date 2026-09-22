package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 单个设备的运行时统计（线程安全），在线状态与计数须与实际采集一致。 */
public class DeviceStats {

    private final String deviceId;
    private volatile boolean online;
    private final AtomicLong offset = new AtomicLong();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong spoolBackpressureCount = new AtomicLong();
    private final AtomicLong consecutiveErrors = new AtomicLong();
    private volatile String lastError;
    private final Map<RejectReason, AtomicLong> rejectReasons = new EnumMap<>(RejectReason.class);

    public DeviceStats(String deviceId) {
        this.deviceId = deviceId;
        for (RejectReason r : RejectReason.values()) {
            rejectReasons.put(r, new AtomicLong());
        }
    }

    public String deviceId() {
        return deviceId;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public long offset() {
        return offset.get();
    }

    public void setOffset(long v) {
        offset.set(v);
    }

    public long acceptedCount() {
        return acceptedCount.get();
    }

    public void incrementAccepted(long n) {
        acceptedCount.addAndGet(n);
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    public void incrementRejected(RejectReason reason) {
        rejectedCount.incrementAndGet();
        rejectReasons.get(reason).incrementAndGet();
    }

    public long spoolBackpressureCount() {
        return spoolBackpressureCount.get();
    }

    public void incrementBackpressure() {
        spoolBackpressureCount.incrementAndGet();
    }

    public long consecutiveErrors() {
        return consecutiveErrors.get();
    }

    public void recordError(String message) {
        consecutiveErrors.incrementAndGet();
        this.lastError = message;
    }

    public void clearError() {
        consecutiveErrors.set(0);
        this.lastError = null;
    }

    public String lastError() {
        return lastError;
    }

    /** 各拒绝原因计数快照。 */
    public Map<RejectReason, Long> rejectReasonsSnapshot() {
        Map<RejectReason, Long> snap = new EnumMap<>(RejectReason.class);
        for (Map.Entry<RejectReason, AtomicLong> e : rejectReasons.entrySet()) {
            snap.put(e.getKey(), e.getValue().get());
        }
        return snap;
    }
}

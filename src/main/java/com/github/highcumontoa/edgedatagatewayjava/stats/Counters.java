package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 网关统计计数器（线程安全）。 */
public final class Counters {

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Map<RejectReason, AtomicLong> rejected = new EnumMap<>(RejectReason.class);

    public Counters() {
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, new AtomicLong());
        }
    }

    public void incrementPolled() {
        polled.incrementAndGet();
    }

    public void incrementAccepted() {
        accepted.incrementAndGet();
    }

    public void incrementRejected(RejectReason reason) {
        rejected.get(reason).incrementAndGet();
    }

    /** 每成功确认一批增加实际到达下游的条数。 */
    public void incrementDelivered(int n) {
        delivered.addAndGet(n);
    }

    /** 缓冲溢出丢弃记录（DROP_OLDEST）或拒绝新记录（REJECT_NEWEST）计数。 */
    public void addDropped(int n) {
        dropped.addAndGet(n);
    }

    public long polled() {
        return polled.get();
    }

    public long accepted() {
        return accepted.get();
    }

    public long delivered() {
        return delivered.get();
    }

    public long dropped() {
        return dropped.get();
    }

    public Map<RejectReason, Long> rejectedByReason() {
        EnumMap<RejectReason, Long> snapshot = new EnumMap<>(RejectReason.class);
        rejected.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    public long rejectedTotal() {
        return rejected.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("polled=").append(polled.get())
                .append(", accepted=").append(accepted.get())
                .append(", delivered=").append(delivered.get())
                .append(", dropped=").append(dropped.get())
                .append(", rejected={");
        boolean first = true;
        for (Map.Entry<RejectReason, AtomicLong> e : rejected.entrySet()) {
            long v = e.getValue().get();
            if (v == 0) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(v);
            first = false;
        }
        return sb.append('}').toString();
    }
}

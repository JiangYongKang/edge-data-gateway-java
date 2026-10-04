package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关全局统计计数器（线程安全；由网关在一致性写锁内更新）。
 *
 * <p>溢出分两项：{@code droppedOldest}（DROP_OLDEST 弹出的已接受记录）与
 * {@code shedNewest}（REJECT_NEWEST 消耗 seq 但未入缓冲的缺口），
 * 二者之和即总丢弃/缺口数 {@code dropped}，用于 seq 守恒核对。
 */
public final class Counters {

    private final AtomicLong polled = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong droppedOldest = new AtomicLong();
    private final AtomicLong shedNewest = new AtomicLong();
    private final Map<RejectReason, AtomicLong> rejected = new EnumMap<>(RejectReason.class);

    public Counters() {
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, new AtomicLong());
        }
    }

    /** 重启基线恢复：用持久化值接续，不清零重算。 */
    public void restore(long polled, long accepted, long delivered,
                        long droppedOldest, long shedNewest,
                        Map<RejectReason, Long> rejectedByReason) {
        this.polled.set(polled);
        this.accepted.set(accepted);
        this.delivered.set(delivered);
        this.droppedOldest.set(droppedOldest);
        this.shedNewest.set(shedNewest);
        rejected.forEach((k, v) -> v.set(rejectedByReason.getOrDefault(k, 0L)));
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
    public void incrementDelivered(long n) {
        delivered.addAndGet(n);
    }

    /** DROP_OLDEST 弹出的已接受记录条数。 */
    public void addDroppedOldest(long n) {
        droppedOldest.addAndGet(n);
    }

    /** REJECT_NEWEST 消耗 seq 但未入缓冲的条数。 */
    public void addShedNewest(long n) {
        shedNewest.addAndGet(n);
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

    public long droppedOldest() {
        return droppedOldest.get();
    }

    public long shedNewest() {
        return shedNewest.get();
    }

    /** 总丢弃/缺口数 = DROP_OLDEST + REJECT_NEWEST。 */
    public long dropped() {
        return droppedOldest.get() + shedNewest.get();
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
                .append(", droppedOldest=").append(droppedOldest.get())
                .append(", shedNewest=").append(shedNewest.get())
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

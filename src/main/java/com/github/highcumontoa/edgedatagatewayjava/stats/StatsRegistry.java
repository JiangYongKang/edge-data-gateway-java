package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 + 逐设备统计登记处。
 *
 * <p>所有写操作与 {@link #snapshot()} 都在同一把锁（本实例）内完成，
 * 因此快照自身永远自洽：同一快照里“全局计数 == 逐设备计数之和”必然成立。
 *
 * <p>计数口径：
 * <ul>
 *   <li>polls = okPolls + pollFailures（每次轮询必居其一）；</li>
 *   <li>accepted：通过全部校验并成功写入缓冲的记录；</li>
 *   <li>rejectedByReason：归一化拒绝 + 网关乱序拒绝（按原因分开）；</li>
 *   <li>rejectedNewest：缓冲满且策略为 REJECT_NEWEST 时被拒的新记录（消耗 seq）；</li>
 *   <li>droppedOldest：缓冲满且策略为 DROP_OLDEST 时被挤出的最旧未确认记录；</li>
 *   <li>delivered：补传批次被确认后实际到达下游的记录（逐设备归属）。</li>
 * </ul>
 */
public final class StatsRegistry {

    private long polled;
    private long accepted;
    private long delivered;
    private long droppedOldest;
    private long rejectedNewest;
    private final Map<RejectReason, Long> rejectedByReason = new EnumMap<>(RejectReason.class);
    private final Map<String, Dev> devices = new LinkedHashMap<>();

    public StatsRegistry() {
        for (RejectReason r : RejectReason.values()) {
            rejectedByReason.put(r, 0L);
        }
    }

    public synchronized void recordPolled(String deviceId) {
        polled++;
        dev(deviceId).polls++;
    }

    public synchronized void recordPollSuccess(String deviceId) {
        dev(deviceId).okPolls++;
    }

    public synchronized void recordPollFailure(String deviceId, String error) {
        dev(deviceId).pollFailures++;
    }

    public synchronized void recordInternalError(String deviceId) {
        dev(deviceId).internalErrors++;
    }

    public synchronized void recordAccepted(String deviceId) {
        accepted++;
        dev(deviceId).accepted++;
    }

    public synchronized void recordRejected(String deviceId, RejectReason reason) {
        rejectedByReason.merge(reason, 1L, Long::sum);
        dev(deviceId).rejectedByReason.merge(reason, 1L, Long::sum);
    }

    public synchronized void recordDelivered(String deviceId) {
        delivered++;
        dev(deviceId).delivered++;
    }

    public synchronized void recordDroppedOldest(String deviceId, int n) {
        droppedOldest += n;
        dev(deviceId).droppedOldest += n;
    }

    public synchronized void recordRejectedNewest(String deviceId) {
        rejectedNewest++;
        dev(deviceId).rejectedNewest++;
    }

    public synchronized StatsSnapshot snapshot() {
        Map<String, DeviceCounters> devs = new LinkedHashMap<>();
        devices.forEach((id, d) -> devs.put(id, d.toCounters(id)));
        return new StatsSnapshot(System.currentTimeMillis(), polled, accepted, delivered,
                droppedOldest, rejectedNewest,
                new EnumMap<>(rejectedByReason), devs);
    }

    /** 导出可持久化视图（逐设备计数；全局计数可由逐设备求和推导，恢复时保证一致）。 */
    public synchronized List<DeviceCounters> persistedView() {
        List<DeviceCounters> out = new ArrayList<>();
        devices.forEach((id, d) -> out.add(d.toCounters(id)));
        return out;
    }

    /** 启动时恢复逐设备计数基线（重启续算，不清零、不重复计数）。 */
    public synchronized void restore(List<DeviceCounters> restored) {
        for (DeviceCounters c : restored) {
            Dev d = dev(c.deviceId());
            d.polls += c.polls();
            d.okPolls += c.okPolls();
            d.pollFailures += c.pollFailures();
            d.internalErrors += c.internalErrors();
            d.accepted += c.accepted();
            c.rejectedByReason().forEach((r, n) -> d.rejectedByReason.merge(r, n, Long::sum));
            d.delivered += c.delivered();
            d.droppedOldest += c.droppedOldest();
            d.rejectedNewest += c.rejectedNewest();

            polled += c.polls();
            accepted += c.accepted();
            delivered += c.delivered();
            droppedOldest += c.droppedOldest();
            rejectedNewest += c.rejectedNewest();
            c.rejectedByReason().forEach((r, n) -> rejectedByReason.merge(r, n, Long::sum));
        }
    }

    private Dev dev(String deviceId) {
        return devices.computeIfAbsent(deviceId, id -> new Dev());
    }

    // ---- 兼容旧 Counters 的只读访问（测试与演示使用）----

    public long polled() {
        return snapshot().polled();
    }

    public long accepted() {
        return snapshot().accepted();
    }

    public long delivered() {
        return snapshot().delivered();
    }

    public long dropped() {
        return snapshot().dropped();
    }

    public long rejectedTotal() {
        return snapshot().rejectedTotal();
    }

    public Map<RejectReason, Long> rejectedByReason() {
        return snapshot().rejectedByReason();
    }

    /** 逐设备可变计数（仅在锁内访问）。 */
    private static final class Dev {
        long polls;
        long okPolls;
        long pollFailures;
        long internalErrors;
        long accepted;
        final Map<RejectReason, Long> rejectedByReason = new EnumMap<>(RejectReason.class);
        long delivered;
        long droppedOldest;
        long rejectedNewest;

        DeviceCounters toCounters(String id) {
            return new DeviceCounters(id, polls, okPolls, pollFailures, internalErrors,
                    accepted, new EnumMap<>(rejectedByReason), delivered, droppedOldest,
                    rejectedNewest);
        }
    }
}

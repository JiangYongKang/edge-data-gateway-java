package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.EnumMap;
import java.util.Map;

/**
 * 单设备累计计数器。
 *
 * <p>口径（每条读数的处置路径互斥且穷尽，保证守恒）：
 * <ul>
 *   <li>轮询：{@code polled} 为发起次数；{@code failedPolls} 为抛异常次数；
 *       {@code successfulPolls = polled - failedPolls}。</li>
 *   <li>读数：{@code received = accepted + shedNewest + Σ_reason rejected}。
 *       <ul>
 *         <li>{@code accepted}：通过网关全部校验、真正进入缓冲并分配 seq；</li>
 *         <li>{@code rejected[reason]}：归一化拒绝或网关乱序拒绝（原因分开）；</li>
 *         <li>{@code shedNewest}：REJECT_NEWEST 溢出，seq 已消耗但记录未入缓冲。</li>
 *       </ul>
 *   </li>
 *   <li>已接受记录的去向：{@code accepted = delivered + droppedOldest + pending}，
 *       其中 {@code droppedOldest} 为本设备记录被 DROP_OLDEST 弹出的条数，
 *       {@code delivered} 为下游已确认条数，{@code pending} 由缓冲实时统计。</li>
 * </ul>
 *
 * <p>非线程安全：由网关在一致性写锁内更新；重启时通过 {@link #restore} 衔接基线，
 * 不清零重算。
 */
public final class DeviceCounters {

    private long polled;
    private long failedPolls;
    private long received;
    private long accepted;
    private long shedNewest;
    private long droppedOldest;
    private long delivered;
    private final Map<RejectReason, Long> rejected = new EnumMap<>(RejectReason.class);

    public DeviceCounters() {
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, 0L);
        }
    }

    /** 发起一次轮询。 */
    public void recordPoll() {
        polled++;
    }

    /** 该次轮询抛异常（设备真实失败）。 */
    public void recordPollFailure() {
        failedPolls++;
    }

    /** 读数通过全部校验并进入缓冲（同时计入 received 与 accepted）。 */
    public void recordAccepted() {
        received++;
        accepted++;
    }

    /** 读数被拒绝（按原因分开计数；同时计入 received）。 */
    public void recordRejected(RejectReason reason) {
        received++;
        rejected.merge(reason, 1L, Long::sum);
    }

    /** REJECT_NEWEST：读数未入缓冲但 seq 已消耗（同时计入 received）。 */
    public void recordShedNewest() {
        received++;
        shedNewest++;
    }

    /** 本设备已接受记录被 DROP_OLDEST 弹出 n 条。 */
    public void addDroppedOldest(long n) {
        droppedOldest += n;
    }

    /** 本设备记录被下游确认 n 条。 */
    public void addDelivered(long n) {
        delivered += n;
    }

    /** 重启基线恢复（持久化值直接接续，不重新计数）。 */
    public void restore(long polled, long failedPolls, long received, long accepted,
                        long shedNewest, long droppedOldest, long delivered,
                        Map<RejectReason, Long> rejectedByReason) {
        this.polled = polled;
        this.failedPolls = failedPolls;
        this.received = received;
        this.accepted = accepted;
        this.shedNewest = shedNewest;
        this.droppedOldest = droppedOldest;
        this.delivered = delivered;
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, rejectedByReason.getOrDefault(reason, 0L));
        }
    }

    public long polled() {
        return polled;
    }

    public long failedPolls() {
        return failedPolls;
    }

    public long successfulPolls() {
        return polled - failedPolls;
    }

    public long received() {
        return received;
    }

    public long accepted() {
        return accepted;
    }

    public long shedNewest() {
        return shedNewest;
    }

    public long droppedOldest() {
        return droppedOldest;
    }

    public long delivered() {
        return delivered;
    }

    public Map<RejectReason, Long> rejectedByReason() {
        return Map.copyOf(rejected);
    }

    public long rejectedTotal() {
        return rejected.values().stream().mapToLong(Long::longValue).sum();
    }
}

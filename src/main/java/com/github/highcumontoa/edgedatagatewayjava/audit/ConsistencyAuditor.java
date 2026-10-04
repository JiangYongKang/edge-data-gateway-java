package com.github.highcumontoa.edgedatagatewayjava.audit;

import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferView;
import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.stats.DeviceCounters;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsRegistry;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统计一致性核对器。
 *
 * <p>一致性切面：统计快照在单锁内生成，本身自洽；与缓冲联动的计数
 * （accepted / delivered / droppedOldest / rejectedNewest）只会在缓冲
 * append/confirm 之后变化，因此“读缓冲视图 → 读统计快照 → 再读缓冲视图，
 * 两次视图相等”即构成一个一致性切面。采集与补传并发进行时若切面不稳定
 * 就重试；超过限定次数返回 {@link AuditReport.Status#UNSTABLE}，
 * 绝不把不同时间点的计数拼成一份自相矛盾的报告。
 *
 * <p>核对口径：
 * <ul>
 *   <li>设备级轮询守恒：polls == okPolls + pollFailures；</li>
 *   <li>全局 == 逐设备之和：polled / accepted / delivered / droppedOldest /
 *       rejectedNewest / 各拒绝原因；</li>
 *   <li>序号守恒：nextSeq == accepted + rejectedNewest（每个被接受的记录
 *       消耗一个 seq，REJECT_NEWEST 也消耗一个）；</li>
 *   <li>流向守恒（全局与逐设备）：accepted == delivered + droppedOldest + pending。</li>
 * </ul>
 */
public final class ConsistencyAuditor {

    private static final int MAX_ATTEMPTS = 100;

    private final StatsRegistry stats;
    private final SpoolBuffer buffer;

    public ConsistencyAuditor(StatsRegistry stats, SpoolBuffer buffer) {
        this.stats = stats;
        this.buffer = buffer;
    }

    public AuditReport audit() {
        StatsSnapshot snap = null;
        BufferView view = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            BufferView before = buffer.view();
            snap = stats.snapshot();
            BufferView after = buffer.view();
            if (before.equals(after)) {
                view = after;
                break;
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (view == null) {
            // 并发写持续不断：不给出任何核对结论，避免自相矛盾
            return new AuditReport(System.currentTimeMillis(),
                    AuditReport.Status.UNSTABLE, snap, buffer.view(), List.of());
        }
        List<ViolationHolder> found = new ArrayList<>();
        checkDevicePollAccounting(snap, found);
        checkGlobalVsDeviceSum(snap, found);
        checkSeqAccounting(snap, view, found);
        checkFlowAccounting(snap, view, found);

        List<AuditReport.Violation> violations = found.stream()
                .map(v -> new AuditReport.Violation(v.deviceId, v.category,
                        v.expected, v.actual, v.detail))
                .toList();
        return new AuditReport(System.currentTimeMillis(),
                violations.isEmpty()
                        ? AuditReport.Status.CONSISTENT : AuditReport.Status.INCONSISTENT,
                snap, view, violations);
    }

    /** 设备级：每次轮询必居“成功/失败”其一。 */
    private void checkDevicePollAccounting(StatsSnapshot snap, List<ViolationHolder> out) {
        for (DeviceCounters d : snap.devices().values()) {
            long expected = d.okPolls() + d.pollFailures();
            if (d.polls() != expected) {
                out.add(new ViolationHolder(d.deviceId(),
                        AuditReport.Category.DEVICE_POLL_ACCOUNTING,
                        expected, d.polls(),
                        "polls != okPolls + pollFailures (" + d.okPolls()
                                + " + " + d.pollFailures() + ")"));
            }
        }
    }

    /** 全局计数必须等于逐设备计数之和。 */
    private void checkGlobalVsDeviceSum(StatsSnapshot snap, List<ViolationHolder> out) {
        long sumPolled = 0, sumAccepted = 0, sumDelivered = 0,
                sumDroppedOldest = 0, sumRejectedNewest = 0;
        Map<RejectReason, Long> sumRejected = new java.util.EnumMap<>(RejectReason.class);
        for (RejectReason r : RejectReason.values()) {
            sumRejected.put(r, 0L);
        }
        for (DeviceCounters d : snap.devices().values()) {
            sumPolled += d.polls();
            sumAccepted += d.accepted();
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumRejectedNewest += d.rejectedNewest();
            d.rejectedByReason().forEach((r, n) -> sumRejected.merge(r, n, Long::sum));
        }
        checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                "polled", sumPolled, snap.polled());
        checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                "accepted", sumAccepted, snap.accepted());
        checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                "delivered", sumDelivered, snap.delivered());
        checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                "droppedOldest", sumDroppedOldest, snap.droppedOldest());
        checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                "rejectedNewest", sumRejectedNewest, snap.rejectedNewest());
        for (RejectReason r : RejectReason.values()) {
            long global = snap.rejectedByReason().getOrDefault(r, 0L);
            checkEq(out, AuditReport.Category.GLOBAL_VS_DEVICE_SUM,
                    "rejected[" + r + "]", sumRejected.get(r), global);
        }
    }

    /** 序号守恒：已分配 seq 数 == 被接受记录数 + REJECT_NEWEST 消耗数。 */
    private void checkSeqAccounting(StatsSnapshot snap, BufferView view,
                                    List<ViolationHolder> out) {
        long expected = snap.accepted() + snap.rejectedNewest();
        if (view.nextSeq() != expected) {
            out.add(new ViolationHolder(null, AuditReport.Category.SEQ_ACCOUNTING,
                    expected, view.nextSeq(),
                    "nextSeq != accepted + rejectedNewest (" + snap.accepted()
                            + " + " + snap.rejectedNewest() + ")"));
        }
    }

    /** 流向守恒：被接受的记录要么已送达、要么被溢出丢弃、要么仍在缓冲。 */
    private void checkFlowAccounting(StatsSnapshot snap, BufferView view,
                                     List<ViolationHolder> out) {
        long expectedGlobal = snap.delivered() + snap.droppedOldest() + view.pending();
        if (snap.accepted() != expectedGlobal) {
            out.add(new ViolationHolder(null, AuditReport.Category.FLOW_ACCOUNTING,
                    expectedGlobal, snap.accepted(),
                    "accepted != delivered + droppedOldest + pending ("
                            + snap.delivered() + " + " + snap.droppedOldest()
                            + " + " + view.pending() + ")"));
        }
        Set<String> deviceIds = new LinkedHashSet<>(snap.devices().keySet());
        deviceIds.addAll(view.pendingByDevice().keySet());
        for (String id : deviceIds) {
            DeviceCounters d = snap.devices().get(id);
            long accepted = d == null ? 0 : d.accepted();
            long delivered = d == null ? 0 : d.delivered();
            long droppedOldest = d == null ? 0 : d.droppedOldest();
            long pending = view.pendingByDevice().getOrDefault(id, 0);
            long expected = delivered + droppedOldest + pending;
            if (accepted != expected) {
                out.add(new ViolationHolder(id, AuditReport.Category.FLOW_ACCOUNTING,
                        expected, accepted,
                        "device accepted != delivered + droppedOldest + pending ("
                                + delivered + " + " + droppedOldest + " + " + pending + ")"));
            }
        }
    }

    private void checkEq(List<ViolationHolder> out, AuditReport.Category category,
                         String counter, long expected, long actual) {
        if (expected != actual) {
            out.add(new ViolationHolder(null, category, expected, actual,
                    "global " + counter + " != sum of per-device " + counter));
        }
    }

    /** 汇总前的临时载体。 */
    private record ViolationHolder(String deviceId, AuditReport.Category category,
                                   long expected, long actual, String detail) {
    }
}

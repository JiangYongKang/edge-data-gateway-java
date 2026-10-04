package com.github.highcumontoa.edgedatagatewayjava.stats;

import java.util.List;
import java.util.Map;

/**
 * 运行时统计一致性核对结论。
 *
 * <p>整份报告在网关一致性读锁内生成：采集与补传并发进行时，报告本身也是
 * 对同一状态快照自洽的一版，不会自相矛盾。
 *
 * <p>全局口径：
 * <ol>
 *   <li>G-DEV-ACCEPT：Σ设备 accepted == 全局 accepted；</li>
 *   <li>G-DEV-DELIVERED：Σ设备 delivered == 全局 delivered；</li>
 *   <li>G-DEV-DROPPED：Σ设备(droppedOldest+shedNewest) == 全局 dropped；</li>
 *   <li>G-SEQ：nextSeq == 全局 accepted + 全局 shedNewest（已分配 seq 数）；</li>
 *   <li>G-LIFECYCLE：已分配 seq 数 == delivered + droppedTotal + pending
 *       （droppedTotal = droppedOldest 合计 + shedNewest 合计）。</li>
 * </ol>
 */
public record ReconciliationReport(
        long atMillis,
        boolean consistent,
        long nextSeq,
        long confirmedSeq,
        long globalPolled,
        long globalAccepted,
        long globalRejected,
        long globalDelivered,
        long globalDroppedOldest,
        long globalShedNewest,
        int globalPending,
        List<ReconciliationIssue> issues,
        Map<String, DeviceReconciliation> devices
) {
}

package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;

import java.util.List;

/**
 * 单设备核对结论。
 *
 * <p>核对口径：
 * <ol>
 *   <li>POLL：successfulPolls + failedPolls == polled；</li>
 *   <li>RECEIVED：accepted + shedNewest + Σrejected == received；</li>
 *   <li>SEQ-ACCEPT：accepted == delivered + droppedOldest + pending(本设备)；</li>
 *   <li>REJECT：拒绝总数由各原因分项求和得到（分项逐项可核对）。</li>
 * </ol>
 */
public record DeviceReconciliation(
        String deviceId,
        boolean online,
        DeviceHealth health,
        long polled,
        long successfulPolls,
        long failedPolls,
        long rejectedTotal,
        long received,
        long accepted,
        long shedNewest,
        long droppedOldest,
        long delivered,
        long pending,
        boolean consistent,
        List<ReconciliationIssue> issues
) {
}

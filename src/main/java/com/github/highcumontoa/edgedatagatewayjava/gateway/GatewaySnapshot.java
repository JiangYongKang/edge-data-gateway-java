package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.stats.ReconciliationReport;

import java.util.Map;

/**
 * 网关状态快照（在线状态、统计与一致性核对结论）。
 *
 * <p>快照（含 {@link #reconciliation()}）在同一把一致性读锁内生成，
 * 采集与补传并发进行时，数字与核对结论取自同一状态版本，不会自相矛盾。
 */
public record GatewaySnapshot(
        long nowMillis,
        boolean uplinkConnected,
        long nextSeq,
        long confirmedSeq,
        int pendingCount,
        long polled,
        long accepted,
        long delivered,
        long dropped,
        Map<RejectReason, Long> rejectedByReason,
        Map<String, DeviceStats> devices,
        ReconciliationReport reconciliation
) {
}

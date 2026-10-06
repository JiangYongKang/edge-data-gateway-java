package com.github.highcumontoa.edgedatagatewayjava.collect;

/**
 * 单设备统计视图（不可变快照）。
 *
 * <p>计数口径（由网关从 StatsRegistry 单锁快照派生，与整体统计同源）：
 * <ul>
 *   <li>pollCount = 注册表逐台 polls；</li>
 *   <li>errorCount = 注册表逐台 pollFailures + internalErrors；</li>
 *   <li>acceptedCount = 注册表逐台 accepted（通过全部校验并成功写入缓冲；
 *       乱序、缓冲满拒新等都不算接受）；</li>
 *   <li>rejectedCount = 注册表逐台 rejectedByReason 之和 + rejectedNewest。</li>
 * </ul>
 * 因此任意时刻：各设备 acceptedCount 之和 == 整体 accepted，
 * 各设备 rejectedCount 之和 == 整体各拒绝原因之和 + rejectedNewest；
 * 一条读数要么算接受、要么算拒绝，不会在设备侧算接受、在整体侧算拒绝。
 */
public record DeviceStats(
        String deviceId,
        DeviceStatus status,
        long pollCount,
        long errorCount,
        long acceptedCount,
        long rejectedCount,
        Long lastPollAt,
        Long lastErrorAt,
        String lastError
) {
}

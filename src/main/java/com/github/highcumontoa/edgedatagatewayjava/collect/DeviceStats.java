package com.github.highcumontoa.edgedatagatewayjava.collect;

/**
 * 单设备采集统计快照。
 *
 * <p>与整体统计（StatsRegistry 逐设备计数）的口径关系：
 * <ul>
 *   <li>{@code acceptedCount} == 逐设备 accepted（通过全部校验并写入缓冲）；</li>
 *   <li>{@code rejectedCount} == 逐设备各拒绝原因之和（归一化拒绝 + 网关乱序拒绝）；</li>
 *   <li>{@code droppedNewestCount} == 逐设备 rejectedNewest（缓冲满拒新）。</li>
 * </ul>
 * 一条读数要么算接受、要么算拒绝、要么算拒新，不会两侧各算一半。
 */
public record DeviceStats(
        String deviceId,
        DeviceStatus status,
        long pollCount,
        long errorCount,
        long acceptedCount,
        long rejectedCount,
        long droppedNewestCount,
        Long lastPollAt,
        Long lastErrorAt,
        String lastError
) {
}

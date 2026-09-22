package com.github.highcumontoa.edgedatagatewayjava.collect;

/** 单设备采集统计快照。 */
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

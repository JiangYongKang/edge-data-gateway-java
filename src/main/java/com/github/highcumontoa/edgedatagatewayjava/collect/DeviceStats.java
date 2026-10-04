package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthTransition;

/**
 * 单设备采集统计与健康快照。
 *
 * <p>在线状态（{@link #status()}，最近一次轮询是否成功）与健康分级
 * （{@link #health()}）正交：上行中断不会改变二者中的任何一个；
 * 健康分级反映本设备连续轮询成败的判定结果。
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
        String lastError,
        // ---- 健康与一致性核对扩展字段 ----
        DeviceHealth health,
        int consecutiveFailures,
        int consecutiveSuccesses,
        HealthTransition lastHealthTransition,
        long receivedCount,
        long shedNewestCount,
        long droppedOldestCount,
        long deliveredCount,
        long pendingCount
) {
    /** 旧字段构造器（扩展字段补默认值），保持原有调用方/断言兼容。 */
    public DeviceStats(String deviceId, DeviceStatus status,
                       long pollCount, long errorCount,
                       long acceptedCount, long rejectedCount,
                       Long lastPollAt, Long lastErrorAt, String lastError) {
        this(deviceId, status, pollCount, errorCount, acceptedCount, rejectedCount,
                lastPollAt, lastErrorAt, lastError,
                DeviceHealth.HEALTHY, 0, 0, null,
                acceptedCount, 0L, 0L, 0L, 0L);
    }
}

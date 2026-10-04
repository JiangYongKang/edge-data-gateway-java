package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthTransition;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.Map;

/**
 * 单设备运行时状态的持久化快照（计数器以原始字段承载，拒绝按原因分项）。
 */
public record DeviceRuntimeState(
        boolean online,
        DeviceHealth health,
        int consecutiveFailures,
        int consecutiveSuccesses,
        HealthTransition lastTransition,
        long lastPollAt,
        long lastFailureAt,
        long lastSuccessAt,
        String lastError,
        Map<RejectReason, Long> rejectedByReason,
        long polled,
        long failedPolls,
        long received,
        long accepted,
        long shedNewest,
        long droppedOldest,
        long delivered
) {
    /** 全新设备的初始状态。 */
    public static DeviceRuntimeState fresh() {
        return new DeviceRuntimeState(true, DeviceHealth.HEALTHY, 0, 0, null,
                0L, 0L, 0L, null, Map.of(),
                0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}

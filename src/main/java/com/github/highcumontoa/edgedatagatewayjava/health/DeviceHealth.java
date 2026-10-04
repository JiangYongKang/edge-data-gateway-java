package com.github.highcumontoa.edgedatagatewayjava.health;

/** 单设备健康快照（不可变）。每次状态切换都能查到最近一次切换的原因与时间。 */
public record DeviceHealth(
        String deviceId,
        HealthState state,
        long consecutiveFailures,
        long consecutiveSuccesses,
        String lastError,
        Long lastErrorAt,
        Long lastTransitionAt,
        String lastTransitionReason
) {
}

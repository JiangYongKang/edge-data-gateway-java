package com.github.highcumontoa.edgedatagatewayjava.health;

/**
 * 最近一次健康状态切换记录。
 *
 * @param from    切换前状态（首次进入时为 null）
 * @param to      切换后状态
 * @param reason  切换原因（确定性描述）
 * @param atMillis 切换时间（epoch millis）
 */
public record HealthTransition(
        DeviceHealth from,
        DeviceHealth to,
        String reason,
        long atMillis
) {
}

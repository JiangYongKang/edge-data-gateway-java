package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;

import java.util.Map;

/** 网关状态快照（在线状态、统计与设备健康）。 */
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
        Map<com.github.highcumontoa.edgedatagatewayjava.model.RejectReason, Long> rejectedByReason,
        Map<String, DeviceStats> devices,
        Map<String, DeviceHealth> health
) {
}

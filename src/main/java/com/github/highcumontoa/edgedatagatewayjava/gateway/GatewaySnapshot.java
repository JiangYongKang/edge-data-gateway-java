package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;

import java.util.Map;

/** 网关状态快照（在线状态与统计）。 */
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
        Map<String, DeviceStats> devices
) {
}

package com.github.highcumontoa.edgedatagatewayjava.web;

import java.util.List;

/** 网关状态视图（REST 返回）。骨架阶段仅为结构占位。 */
public record GatewayStatus(
        boolean running,
        boolean channelOnline,
        int spoolPending,
        int spoolCapacity,
        long spoolNextSeq,
        long deliveredCount,
        List<DeviceStatusView> devices
) {
    public record DeviceStatusView(
            String deviceId,
            boolean online,
            long offset,
            long accepted,
            long rejected,
            long backpressure,
            long consecutiveErrors,
            String lastError
    ) {
    }
}

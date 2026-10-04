package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.Map;

/** 单设备统计计数快照（不可变），作为统计一致性核对的逐设备基线。 */
public record DeviceCounters(
        String deviceId,
        long polls,
        long okPolls,
        long pollFailures,
        long internalErrors,
        long accepted,
        Map<RejectReason, Long> rejectedByReason,
        long delivered,
        long droppedOldest,
        long rejectedNewest
) {
    public DeviceCounters {
        rejectedByReason = Map.copyOf(rejectedByReason);
    }

    public long rejectedTotal() {
        return rejectedByReason.values().stream().mapToLong(Long::longValue).sum();
    }
}

package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.Map;

/**
 * 全部统计计数的一致性快照（不可变）。
 *
 * <p>由 {@link StatsRegistry} 在单一锁内生成，因此同一快照内部
 * “全局计数 == 逐设备计数之和” 等关系不会被并发写撕裂。
 */
public record StatsSnapshot(
        long takenAtMillis,
        long polled,
        long accepted,
        long delivered,
        long droppedOldest,
        long rejectedNewest,
        Map<RejectReason, Long> rejectedByReason,
        Map<String, DeviceCounters> devices
) {
    public StatsSnapshot {
        rejectedByReason = Map.copyOf(rejectedByReason);
        devices = Map.copyOf(devices);
    }

    public long dropped() {
        return droppedOldest + rejectedNewest;
    }

    public long rejectedTotal() {
        return rejectedByReason.values().stream().mapToLong(Long::longValue).sum();
    }
}

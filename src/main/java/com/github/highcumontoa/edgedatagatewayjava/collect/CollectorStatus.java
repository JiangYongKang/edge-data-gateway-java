package com.github.highcumontoa.edgedatagatewayjava.collect;

/**
 * 采集运行时的非计数信息（由采集线程写、快照读）。
 *
 * <p>只承载在线状态与时间戳类信息；接受/拒绝/轮询等计数一律以
 * StatsRegistry 为唯一来源，由网关在 deviceStats() 合并输出，
 * 保证逐台视图与整体统计同源自洽。
 */
public record CollectorStatus(
        String deviceId,
        DeviceStatus status,
        Long lastPollAt,
        Long lastErrorAt,
        String lastError
) {
}

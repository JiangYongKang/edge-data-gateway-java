package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 归一化后的上行记录（统一格式）。
 *
 * @param seq       网关全局单调递增序号（缓冲负责分配）
 * @param deviceId  设备标识
 * @param pointId   点位标识
 * @param type      归一化数据类型
 * @param value     归一化值（Integer/Double/Boolean）
 * @param unit      归一化单位（点位期望单位）
 * @param timestamp 原始采集时间戳（epoch millis）
 */
public record NormalizedRecord(
        long seq,
        String deviceId,
        String pointId,
        DataType type,
        Object value,
        String unit,
        long timestamp
) {
}

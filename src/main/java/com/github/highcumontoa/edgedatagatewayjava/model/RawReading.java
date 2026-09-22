package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 设备上报的原始读数。
 *
 * @param deviceId  设备标识
 * @param pointId   点位标识
 * @param rawValue  原始值（可能为 Integer/Long/Double/String/Boolean/null）
 * @param rawUnit   读数携带的单位
 * @param timestamp 采集时间戳（epoch millis），null 表示缺失
 */
public record RawReading(
        String deviceId,
        String pointId,
        Object rawValue,
        String rawUnit,
        Long timestamp
) {
}

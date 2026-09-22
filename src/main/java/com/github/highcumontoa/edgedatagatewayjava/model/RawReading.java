package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 现场设备产生的原始读数（未经归一化，可能畸形）。
 *
 * @param deviceId  来源设备
 * @param pointId   点位标识
 * @param value     原始值，类型可能为 Double/Boolean/String/null
 * @param unit      原始单位，可能缺失
 * @param timestamp 采集时间戳（epoch milli），可能为 null
 */
public record RawReading(
        String deviceId,
        String pointId,
        Object value,
        String unit,
        Long timestamp
) {
}

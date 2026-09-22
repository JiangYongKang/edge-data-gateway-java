package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 归一化后的统一上行记录。
 *
 * @param seq       全局单调递增序列号（入缓冲顺序，补传按其排序）
 * @param deviceId  来源设备
 * @param pointId   点位标识
 * @param dataType  归一化类型
 * @param unit      约定单位
 * @param numeric   数值（DOUBLE 时有值）
 * @param flag      布尔量（BOOLEAN 时有值）
 * @param timestamp 采集时间戳（epoch milli）
 */
public record NormalizedRecord(
        long seq,
        String deviceId,
        String pointId,
        DataType dataType,
        String unit,
        Double numeric,
        Boolean flag,
        long timestamp
) {
}

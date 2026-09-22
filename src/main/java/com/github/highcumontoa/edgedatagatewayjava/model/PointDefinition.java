package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 点位定义。
 *
 * @param pointId    点位标识（设备内唯一）
 * @param dataType   归一化后的数据类型
 * @param unit       期望单位；为空串表示不校验单位（量纲无关点位）
 * @param minInclusive 数值下界（含），null 表示不校验下界
 * @param maxInclusive 数值上界（含），null 表示不校验上界
 */
public record PointDefinition(
        String pointId,
        DataType dataType,
        String unit,
        Double minInclusive,
        Double maxInclusive
) {
}

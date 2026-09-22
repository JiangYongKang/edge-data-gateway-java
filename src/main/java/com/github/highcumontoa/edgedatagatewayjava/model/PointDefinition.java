package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 点位定义：描述一个设备上的采集点的归一化规则。
 *
 * @param pointId  点位标识（设备内唯一）
 * @param dataType 归一化目标类型
 * @param unit     约定单位（读数单位必须与之完全一致）
 * @param min      数值量程下界（含），仅 DOUBLE 使用
 * @param max      数值量程上界（含），仅 DOUBLE 使用
 */
public record PointDefinition(
        String pointId,
        DataType dataType,
        String unit,
        Double min,
        Double max
) {
}

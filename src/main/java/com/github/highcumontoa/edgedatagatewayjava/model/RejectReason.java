package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 读数被拒绝的可区分原因。
 * 每个枚举常量即为一种稳定、可在日志与统计中区分的失败码。
 */
public enum RejectReason {
    /** 点位在设备点位表中不存在。 */
    UNKNOWN_POINT,
    /** 读数缺失（null）。 */
    VALUE_MISSING,
    /** 取值类型与点位声明类型不匹配。 */
    TYPE_MISMATCH,
    /** 单位与点位声明单位不一致。 */
    UNIT_MISMATCH,
    /** 数值超出点位配置的量程（min/max）。 */
    OUT_OF_RANGE,
    /** 时间戳缺失。 */
    TIMESTAMP_MISSING,
    /** 时间戳相对同一设备上一条已接受读数发生乱序（含相等）。 */
    TIMESTAMP_OUT_OF_ORDER
}

package com.github.highcumontoa.edgedatagatewayjava.model;

/** 读数被拒绝的可区分原因。 */
public enum RejectReason {
    UNKNOWN_POINT,
    VALUE_NULL,
    TYPE_MISMATCH,
    OUT_OF_RANGE,
    UNIT_MISMATCH,
    TIMESTAMP_MISSING,
    TIMESTAMP_OUT_OF_ORDER
}

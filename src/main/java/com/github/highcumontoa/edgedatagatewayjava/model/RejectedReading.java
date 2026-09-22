package com.github.highcumontoa.edgedatagatewayjava.model;

/**
 * 被拒绝读数的结构化记录（绝不静默丢弃）。
 *
 * @param raw    原始读数
 * @param reason 可区分的失败原因
 * @param detail 人类可读说明
 * @param rejectedAt 拒绝发生时间（epoch milli）
 */
public record RejectedReading(
        RawReading raw,
        RejectReason reason,
        String detail,
        long rejectedAt
) {
}

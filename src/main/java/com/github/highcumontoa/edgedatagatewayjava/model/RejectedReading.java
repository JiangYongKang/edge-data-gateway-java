package com.github.highcumontoa.edgedatagatewayjava.model;

/** 被拒绝读数及其可区分原因。 */
public record RejectedReading(
        RawReading raw,
        RejectReason reason,
        String detail
) {
}

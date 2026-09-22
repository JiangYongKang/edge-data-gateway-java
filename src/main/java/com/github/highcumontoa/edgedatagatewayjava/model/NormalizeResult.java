package com.github.highcumontoa.edgedatagatewayjava.model;

/** 单条读数归一化结果：要么接受，要么拒绝，二者必居其一。 */
public final class NormalizeResult {
    private final NormalizedRecord accepted;
    private final RejectedReading rejected;

    private NormalizeResult(NormalizedRecord accepted, RejectedReading rejected) {
        this.accepted = accepted;
        this.rejected = rejected;
    }

    public static NormalizeResult accept(NormalizedRecord record) {
        return new NormalizeResult(record, null);
    }

    public static NormalizeResult reject(RejectedReading rejected) {
        return new NormalizeResult(null, rejected);
    }

    public boolean accepted() {
        return accepted != null;
    }

    public NormalizedRecord record() {
        return accepted;
    }

    public RejectedReading rejected() {
        return rejected;
    }
}

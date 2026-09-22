package com.github.highcumontoa.edgedatagatewayjava.normalize;

import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

/** 归一化失败异常，携带可区分的拒绝原因。 */
public class NormalizationException extends RuntimeException {

    private final RejectReason reason;

    public NormalizationException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}

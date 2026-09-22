package com.github.highcumontoa.edgedatagatewayjava.spool;

/** 本地持久缓冲达到容量上限时抛出，触发确定性的背压策略。 */
public class SpoolFullException extends RuntimeException {
    public SpoolFullException(String message) {
        super(message);
    }
}

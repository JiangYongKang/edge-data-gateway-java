package com.github.highcumontoa.edgedatagatewayjava.model;

/** 缓冲达到容量上限时的确定性处理策略。 */
public enum OverflowPolicy {
    /** 丢弃最旧的未确认记录（seq 最小），为新记录腾位。 */
    DROP_OLDEST,
    /** 拒绝新记录，新记录不会进入缓冲。 */
    REJECT_NEWEST
}

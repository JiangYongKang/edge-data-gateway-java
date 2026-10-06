package com.github.highcumontoa.edgedatagatewayjava.collect;

/**
 * 网关对一条“归一化已通过”的读数的最终处置结果。
 *
 * <p>采集线程据此计数，保证一条读数在逐台统计与整体统计中口径一致：
 * 要么算接受、要么算拒绝、要么算缓冲拒新，不会两侧各算一半。
 */
public enum AcceptOutcome {
    /** 通过全部校验（含每设备时间戳单调性）并写入缓冲。 */
    ACCEPTED,
    /** 时间戳严格回退，被网关按 {@code TIMESTAMP_OUT_OF_ORDER} 拒绝。 */
    REJECTED_OUT_OF_ORDER,
    /** 缓冲满且策略为 REJECT_NEWEST，新记录被拒（seq 已消耗，形成可解释缺口）。 */
    DROPPED_NEWEST
}

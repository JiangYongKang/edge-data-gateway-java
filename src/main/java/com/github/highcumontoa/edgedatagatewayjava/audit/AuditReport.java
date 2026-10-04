package com.github.highcumontoa.edgedatagatewayjava.audit;

import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferView;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsSnapshot;

import java.util.List;

/**
 * 统计一致性核对结论（不可变）。
 *
 * <p>一份报告要么基于同一一致性切面给出 CONSISTENT / INCONSISTENT，
 * 要么在并发写持续不断、无法取得稳定切面时给出 UNSTABLE——
 * 绝不会把不同时间点的计数拼成一份自相矛盾的报告。
 */
public record AuditReport(
        long takenAtMillis,
        Status status,
        StatsSnapshot stats,
        BufferView buffer,
        List<Violation> violations
) {
    public AuditReport {
        violations = List.copyOf(violations);
    }

    public enum Status {
        /** 取得一致性切面且所有核对关系成立。 */
        CONSISTENT,
        /** 取得一致性切面且存在不一致，violations 逐条指明设备与计数类别。 */
        INCONSISTENT,
        /** 并发写持续不断，未能在限定重试内取得稳定切面（不给出任何核对结论）。 */
        UNSTABLE
    }

    /** 一条具体的不一致：哪台设备（null 表示全局关系）、哪一类计数、期望与实际。 */
    public record Violation(
            String deviceId,
            Category category,
            long expected,
            long actual,
            String detail
    ) {
    }

    public enum Category {
        /** 设备级：polls != okPolls + pollFailures。 */
        DEVICE_POLL_ACCOUNTING,
        /** 全局计数 != 逐设备计数之和。 */
        GLOBAL_VS_DEVICE_SUM,
        /** 已分配序号 != accepted + rejectedNewest。 */
        SEQ_ACCOUNTING,
        /** 流向守恒：accepted != delivered + droppedOldest + pending（全局或逐设备）。 */
        FLOW_ACCOUNTING
    }
}

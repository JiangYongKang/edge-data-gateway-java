package com.github.highcumontoa.edgedatagatewayjava.config;

import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;

/**
 * 网关配置（与 Spring 解耦，便于测试）。
 *
 * @param degradeAfterFailures 连续失败达到该次数后 HEALTHY/RECOVERING 降级为 DEGRADED
 * @param faultAfterFailures   连续失败达到该次数后判定为 FAULT（严重故障）
 * @param recoverAfterSuccesses 恢复观察期需要的连续成功次数，达到后才回到 HEALTHY
 */
public record GatewayProperties(
        java.nio.file.Path dataDir,
        int bufferCapacity,
        OverflowPolicy overflowPolicy,
        long collectIntervalMillis,
        long drainIntervalMillis,
        int batchSize,
        int degradeAfterFailures,
        int faultAfterFailures,
        int recoverAfterSuccesses
) {
    /** 兼容旧签名：健康阈值取默认值（3 / 6 / 3）。 */
    public GatewayProperties(java.nio.file.Path dataDir, int bufferCapacity,
                             OverflowPolicy overflowPolicy, long collectIntervalMillis,
                             long drainIntervalMillis, int batchSize) {
        this(dataDir, bufferCapacity, overflowPolicy, collectIntervalMillis,
                drainIntervalMillis, batchSize, 3, 6, 3);
    }

    public static GatewayProperties defaults(java.nio.file.Path dataDir) {
        return new GatewayProperties(dataDir, 1000, OverflowPolicy.DROP_OLDEST,
                100L, 50L, 100);
    }
}

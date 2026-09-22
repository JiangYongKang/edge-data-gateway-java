package com.github.highcumontoa.edgedatagatewayjava.config;

import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;

/** 网关配置（与 Spring 解耦，便于测试）。 */
public record GatewayProperties(
        java.nio.file.Path dataDir,
        int bufferCapacity,
        OverflowPolicy overflowPolicy,
        long collectIntervalMillis,
        long drainIntervalMillis,
        int batchSize
) {
    public static GatewayProperties defaults(java.nio.file.Path dataDir) {
        return new GatewayProperties(dataDir, 1000, OverflowPolicy.DROP_OLDEST,
                100L, 50L, 100);
    }
}

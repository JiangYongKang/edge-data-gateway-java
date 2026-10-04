package com.github.highcumontoa.edgedatagatewayjava.config;

import com.github.highcumontoa.edgedatagatewayjava.health.HealthConfig;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;

import java.nio.file.Path;

/**
 * 网关配置（与 Spring 解耦，便于测试）。
 *
 * @param health 设备健康判定阈值（降级失败次数 / 严重故障失败次数 / 恢复观察成功次数）
 */
public record GatewayProperties(
        Path dataDir,
        int bufferCapacity,
        OverflowPolicy overflowPolicy,
        long collectIntervalMillis,
        long drainIntervalMillis,
        int batchSize,
        HealthConfig health
) {
    /** 不含健康阈值的兼容构造器：阈值取 {@link HealthConfig#defaults()}。 */
    public GatewayProperties(Path dataDir, int bufferCapacity,
                             OverflowPolicy overflowPolicy,
                             long collectIntervalMillis,
                             long drainIntervalMillis,
                             int batchSize) {
        this(dataDir, bufferCapacity, overflowPolicy, collectIntervalMillis,
                drainIntervalMillis, batchSize, HealthConfig.defaults());
    }

    public static GatewayProperties defaults(Path dataDir) {
        return new GatewayProperties(dataDir, 1000, OverflowPolicy.DROP_OLDEST,
                100L, 50L, 100, HealthConfig.defaults());
    }
}

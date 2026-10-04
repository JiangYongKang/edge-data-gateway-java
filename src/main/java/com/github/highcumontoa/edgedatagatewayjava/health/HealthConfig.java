package com.github.highcumontoa.edgedatagatewayjava.health;

/**
 * 健康判定阈值（基于本设备连续失败/连续成功的轮询次数）。
 *
 * @param degradedFailures   连续失败次数达到该值 → DEGRADED
 * @param criticalFailures   连续失败次数达到该值 → CRITICAL（必须 &gt; degradedFailures）
 * @param recoverySuccesses  进入 RECOVERING 后，连续成功次数达到该值 → HEALTHY
 */
public record HealthConfig(
        int degradedFailures,
        int criticalFailures,
        int recoverySuccesses
) {
    public HealthConfig {
        if (degradedFailures <= 0 || criticalFailures <= 0 || recoverySuccesses <= 0) {
            throw new IllegalArgumentException("health thresholds must be positive");
        }
        if (criticalFailures <= degradedFailures) {
            throw new IllegalArgumentException(
                    "criticalFailures must be greater than degradedFailures");
        }
    }

    public static HealthConfig defaults() {
        return new HealthConfig(3, 5, 3);
    }
}

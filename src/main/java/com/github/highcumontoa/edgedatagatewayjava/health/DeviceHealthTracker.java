package com.github.highcumontoa.edgedatagatewayjava.health;

/**
 * 单设备健康状态机。
 *
 * <p>健康判定只依据本设备自己真实的轮询结果（成功/失败），与上行通道完全无关：
 * 上行中断、缓冲溢出等通道侧事件不会调用本状态机，因此不会把设备判成不健康。
 *
 * <p>状态转移（d = degradedFailures，c = criticalFailures，r = recoverySuccesses）：
 * <pre>
 *   连续失败：HEALTHY --cf>=d--> DEGRADED --cf>=c--> CRITICAL
 *             RECOVERING/DEGRADED --cf>=c--> CRITICAL
 *   连续成功：DEGRADED/CRITICAL --第1次成功--> RECOVERING
 *             RECOVERING --连续成功>=r--> HEALTHY
 *             RECOVERING 中再失败：cf 重新计数，回到 DEGRADED（或 cf>=c 时 CRITICAL）
 * </pre>
 *
 * <p>非线程安全：由网关在一致性写锁内调用。
 */
public final class DeviceHealthTracker {

    private final String deviceId;
    private final HealthConfig config;

    private DeviceHealth health = DeviceHealth.HEALTHY;
    private int consecutiveFailures = 0;
    private int consecutiveSuccesses = 0;
    private HealthTransition lastTransition = null;
    private long lastFailureAt = 0L;
    private long lastSuccessAt = 0L;

    public DeviceHealthTracker(String deviceId, HealthConfig config) {
        this.deviceId = deviceId;
        this.config = config;
    }

    /** 恢复重启前状态（基线衔接：健康等级与连续失败/成功次数不清零）。 */
    public void restore(DeviceHealth health, int consecutiveFailures, int consecutiveSuccesses,
                        HealthTransition lastTransition, long lastFailureAt, long lastSuccessAt) {
        this.health = health;
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
        this.consecutiveSuccesses = Math.max(0, consecutiveSuccesses);
        this.lastTransition = lastTransition;
        this.lastFailureAt = lastFailureAt;
        this.lastSuccessAt = lastSuccessAt;
    }

    /**
     * 记录一次本设备轮询失败。
     *
     * @return 本次失败导致的状态切换；未切换返回 null
     */
    public HealthTransition recordFailure(String errorSummary, long nowMillis) {
        lastFailureAt = nowMillis;
        consecutiveFailures++;
        consecutiveSuccesses = 0;

        DeviceHealth from = health;
        DeviceHealth to = from;
        String reason = null;
        if (consecutiveFailures >= config.criticalFailures() && from != DeviceHealth.CRITICAL) {
            to = DeviceHealth.CRITICAL;
            reason = "consecutive poll failures reached critical threshold "
                    + config.criticalFailures() + ": " + safe(errorSummary);
        } else if (consecutiveFailures >= config.degradedFailures()
                && from == DeviceHealth.HEALTHY) {
            to = DeviceHealth.DEGRADED;
            reason = "consecutive poll failures reached degraded threshold "
                    + config.degradedFailures() + ": " + safe(errorSummary);
        } else if (from == DeviceHealth.RECOVERING) {
            // 恢复观察期内再次失败：观察中止，回到降级态（严重故障阈值优先）
            to = DeviceHealth.DEGRADED;
            reason = "poll failed again during recovery observation (consecutiveFailures="
                    + consecutiveFailures + "): " + safe(errorSummary);
        }
        return applyTransition(from, to, reason, nowMillis);
    }

    /**
     * 记录一次本设备轮询成功。
     *
     * @return 本次成功导致的状态切换；未切换返回 null
     */
    public HealthTransition recordSuccess(long nowMillis) {
        lastSuccessAt = nowMillis;
        consecutiveSuccesses++;
        consecutiveFailures = 0;

        DeviceHealth from = health;
        DeviceHealth to = from;
        String reason = null;
        if (from == DeviceHealth.DEGRADED || from == DeviceHealth.CRITICAL) {
            // 刚恢复一次不能直接算正常：先进恢复观察
            to = DeviceHealth.RECOVERING;
            reason = "first successful poll after failures, entering recovery observation "
                    + "(need " + config.recoverySuccesses() + " consecutive successes)";
        } else if (from == DeviceHealth.RECOVERING
                && consecutiveSuccesses >= config.recoverySuccesses()) {
            to = DeviceHealth.HEALTHY;
            reason = "stable for " + config.recoverySuccesses()
                    + " consecutive successful polls, recovery confirmed";
        }
        return applyTransition(from, to, reason, nowMillis);
    }

    private HealthTransition applyTransition(DeviceHealth from, DeviceHealth to,
                                             String reason, long nowMillis) {
        if (to == from || reason == null) {
            return null;
        }
        HealthTransition t = new HealthTransition(from, to, reason, nowMillis);
        health = to;
        lastTransition = t;
        return t;
    }

    private static String safe(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    public String deviceId() {
        return deviceId;
    }

    public HealthConfig config() {
        return config;
    }

    public DeviceHealth health() {
        return health;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public HealthTransition lastTransition() {
        return lastTransition;
    }

    public long lastFailureAt() {
        return lastFailureAt;
    }

    public long lastSuccessAt() {
        return lastSuccessAt;
    }
}

package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealthTracker;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthConfig;

/**
 * 单设备运行时聚合：计数器 + 健康状态机 + 最近活动信息。
 *
 * <p>非线程安全：由网关在一致性写锁内变更、读锁内读取。
 */
public final class DeviceRuntime {

    private final String deviceId;
    private final DeviceCounters counters = new DeviceCounters();
    private final DeviceHealthTracker health;

    private boolean online = true;
    private long lastPollAt = 0L;
    private long lastErrorAt = 0L;
    private String lastError = null;

    public DeviceRuntime(String deviceId, HealthConfig healthConfig) {
        this.deviceId = deviceId;
        this.health = new DeviceHealthTracker(deviceId, healthConfig);
    }

    /** 重启恢复：健康状态、连续失败/成功、最近错误、计数基线整体衔接。 */
    public void restore(DeviceRuntimeState state) {
        this.online = state.online();
        this.lastPollAt = state.lastPollAt();
        this.lastErrorAt = state.lastFailureAt();
        this.lastError = state.lastError();
        counters.restore(state.polled(), state.failedPolls(), state.received(),
                state.accepted(), state.shedNewest(), state.droppedOldest(),
                state.delivered(), state.rejectedByReason());
        health.restore(state.health(), state.consecutiveFailures(),
                state.consecutiveSuccesses(), state.lastTransition(),
                state.lastFailureAt(), state.lastSuccessAt());
    }

    /** 生成持久化快照。 */
    public DeviceRuntimeState snapshot() {
        return new DeviceRuntimeState(
                online,
                health.health(),
                health.consecutiveFailures(),
                health.consecutiveSuccesses(),
                health.lastTransition(),
                lastPollAt,
                health.lastFailureAt(),
                health.lastSuccessAt(),
                lastError,
                counters.rejectedByReason(),
                counters.polled(),
                counters.failedPolls(),
                counters.received(),
                counters.accepted(),
                counters.shedNewest(),
                counters.droppedOldest(),
                counters.delivered());
    }

    public String deviceId() {
        return deviceId;
    }

    public DeviceCounters counters() {
        return counters;
    }

    public DeviceHealthTracker health() {
        return health;
    }

    public boolean online() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public long lastPollAt() {
        return lastPollAt;
    }

    public void setLastPollAt(long lastPollAt) {
        this.lastPollAt = lastPollAt;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    public String lastError() {
        return lastError;
    }

    public void noteError(long at, String error) {
        this.lastErrorAt = at;
        this.lastError = error;
    }
}

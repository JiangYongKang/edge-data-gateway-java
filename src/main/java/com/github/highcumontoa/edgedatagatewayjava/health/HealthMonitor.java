package com.github.highcumontoa.edgedatagatewayjava.health;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备健康状态机。
 *
 * <p>输入只有设备真实轮询结果（{@link #onPollSuccess} / {@link #onPollFailure}）；
 * 上行通道中断、缓冲溢出等事件从不进入本类，因此短时断网不会把设备判成不健康。
 *
 * <p>切换规则（阈值来自配置）：
 * <ul>
 *   <li>连续失败 ≥ 故障阈值 → {@link HealthState#FAULT}；</li>
 *   <li>连续失败 ≥ 降级阈值（且未达故障阈值）→ {@link HealthState#DEGRADED}；</li>
 *   <li>恢复观察期内再次出错 → 立即回到 {@link HealthState#DEGRADED}；</li>
 *   <li>DEGRADED/FAULT 下首次成功 → {@link HealthState#RECOVERING}（恢复观察）；</li>
 *   <li>RECOVERING 下连续成功 ≥ 恢复阈值 → {@link HealthState#HEALTHY}。</li>
 * </ul>
 * 每次切换都记录原因与时间，可通过 {@link #snapshot()} 查询。
 */
public final class HealthMonitor {

    /** 状态切换监听器（用于日志与持久化触发）。 */
    public interface TransitionListener {
        void onTransition(DeviceHealth health);
    }

    private final int degradeAfterFailures;
    private final int faultAfterFailures;
    private final int recoverAfterSuccesses;
    private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    private volatile TransitionListener listener;

    public HealthMonitor(int degradeAfterFailures, int faultAfterFailures,
                         int recoverAfterSuccesses) {
        if (degradeAfterFailures <= 0 || faultAfterFailures <= 0 || recoverAfterSuccesses <= 0) {
            throw new IllegalArgumentException("health thresholds must be positive");
        }
        if (faultAfterFailures < degradeAfterFailures) {
            throw new IllegalArgumentException("faultAfterFailures must be >= degradeAfterFailures");
        }
        this.degradeAfterFailures = degradeAfterFailures;
        this.faultAfterFailures = faultAfterFailures;
        this.recoverAfterSuccesses = recoverAfterSuccesses;
    }

    /** 预登记设备（初始 HEALTHY），让快照在首次轮询前就覆盖全部设备。 */
    public synchronized void register(String deviceId) {
        runtimes.computeIfAbsent(deviceId, id -> new Runtime(id));
    }

    public void onPollSuccess(String deviceId) {
        List<DeviceHealth> transitions = new ArrayList<>();
        synchronized (this) {
            Runtime rt = runtime(deviceId);
            rt.consecutiveSuccesses++;
            rt.consecutiveFailures = 0;
            switch (rt.state) {
                case FAULT, DEGRADED -> transition(rt, HealthState.RECOVERING,
                        "采集恢复成功，进入恢复观察期（需连续 " + recoverAfterSuccesses
                                + " 次成功）", transitions);
                case RECOVERING -> {
                    if (rt.consecutiveSuccesses >= recoverAfterSuccesses) {
                        transition(rt, HealthState.HEALTHY,
                                "恢复观察期连续 " + rt.consecutiveSuccesses
                                        + " 次成功，判定恢复正常", transitions);
                    }
                }
                case HEALTHY -> {
                    // 保持正常
                }
            }
        }
        notifyTransitions(transitions);
    }

    public void onPollFailure(String deviceId, Throwable error) {
        List<DeviceHealth> transitions = new ArrayList<>();
        synchronized (this) {
            Runtime rt = runtime(deviceId);
            rt.consecutiveFailures++;
            rt.consecutiveSuccesses = 0;
            rt.lastError = String.valueOf(error);
            rt.lastErrorAt = System.currentTimeMillis();
            if (rt.consecutiveFailures >= faultAfterFailures) {
                transition(rt, HealthState.FAULT,
                        "连续失败 " + rt.consecutiveFailures + " 次 ≥ 故障阈值 "
                                + faultAfterFailures + "，判定严重故障", transitions);
            } else if (rt.consecutiveFailures >= degradeAfterFailures) {
                transition(rt, HealthState.DEGRADED,
                        "连续失败 " + rt.consecutiveFailures + " 次 ≥ 降级阈值 "
                                + degradeAfterFailures + "，反复出错", transitions);
            } else if (rt.state == HealthState.RECOVERING) {
                transition(rt, HealthState.DEGRADED,
                        "恢复观察期内再次出错（连续 " + rt.consecutiveFailures
                                + " 次），退回降级", transitions);
            }
            // HEALTHY 且未达降级阈值：保持正常
        }
        notifyTransitions(transitions);
    }

    public synchronized Map<String, DeviceHealth> snapshot() {
        Map<String, DeviceHealth> out = new LinkedHashMap<>();
        runtimes.forEach((id, rt) -> out.put(id, rt.toHealth()));
        return out;
    }

    public DeviceHealth healthOf(String deviceId) {
        synchronized (this) {
            return runtime(deviceId).toHealth();
        }
    }

    /** 导出可持久化视图。 */
    public synchronized List<DeviceHealth> persistedView() {
        List<DeviceHealth> out = new ArrayList<>();
        runtimes.forEach((id, rt) -> out.add(rt.toHealth()));
        return out;
    }

    /** 启动时恢复（重启续算：健康状态、连续失败/成功次数、最近错误衔接重启前）。 */
    public synchronized void restore(List<DeviceHealth> restored) {
        for (DeviceHealth h : restored) {
            Runtime rt = new Runtime(h.deviceId());
            rt.state = h.state();
            rt.consecutiveFailures = h.consecutiveFailures();
            rt.consecutiveSuccesses = h.consecutiveSuccesses();
            rt.lastError = h.lastError();
            rt.lastErrorAt = h.lastErrorAt();
            rt.lastTransitionAt = h.lastTransitionAt();
            rt.lastTransitionReason = h.lastTransitionReason();
            runtimes.put(h.deviceId(), rt);
        }
    }

    public void setTransitionListener(TransitionListener listener) {
        this.listener = listener;
    }

    private Runtime runtime(String deviceId) {
        return runtimes.computeIfAbsent(deviceId, id -> new Runtime(id));
    }

    private void transition(Runtime rt, HealthState next, String reason,
                            List<DeviceHealth> transitions) {
        if (rt.state == next) {
            return;
        }
        rt.state = next;
        rt.lastTransitionAt = System.currentTimeMillis();
        rt.lastTransitionReason = reason;
        transitions.add(rt.toHealth());
    }

    private void notifyTransitions(List<DeviceHealth> transitions) {
        TransitionListener l = listener;
        if (l != null) {
            for (DeviceHealth h : transitions) {
                l.onTransition(h);
            }
        }
    }

    /** 单设备健康运行时（仅在本类锁内访问）。 */
    private static final class Runtime {
        final String deviceId;
        HealthState state = HealthState.HEALTHY;
        long consecutiveFailures;
        long consecutiveSuccesses;
        String lastError;
        Long lastErrorAt;
        Long lastTransitionAt;
        String lastTransitionReason;

        Runtime(String deviceId) {
            this.deviceId = deviceId;
        }

        DeviceHealth toHealth() {
            return new DeviceHealth(deviceId, state, consecutiveFailures,
                    consecutiveSuccesses, lastError, lastErrorAt,
                    lastTransitionAt, lastTransitionReason);
        }
    }
}

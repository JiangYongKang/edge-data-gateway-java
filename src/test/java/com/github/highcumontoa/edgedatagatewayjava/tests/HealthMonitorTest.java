package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthMonitor;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设备健康状态机：降级、严重故障、恢复观察、复发退回、切换原因与重启恢复。 */
class HealthMonitorTest {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitorTest.class);

    private static HealthMonitor newMonitor() {
        // 降级阈值 2，故障阈值 4，恢复观察需要连续 3 次成功
        return new HealthMonitor(2, 4, 3);
    }

    @Test
    void repeatedFailuresDegradeThenFaultWithRecordedReasons() {
        HealthMonitor m = newMonitor();
        List<DeviceHealth> transitions = new ArrayList<>();
        m.setTransitionListener(transitions::add);

        m.onPollFailure("dev-A", new RuntimeException("boom-1"));
        assertEquals(HealthState.HEALTHY, m.healthOf("dev-A").state(),
                "单次失败不降级（阈值 2）");

        m.onPollFailure("dev-A", new RuntimeException("boom-2"));
        DeviceHealth degraded = m.healthOf("dev-A");
        assertEquals(HealthState.DEGRADED, degraded.state(), "连续 2 次失败降级");
        assertEquals(2, degraded.consecutiveFailures());
        assertNotNull(degraded.lastTransitionAt(), "切换时间已记录");
        assertTrue(degraded.lastTransitionReason().contains("降级阈值"),
                "切换原因可解释: " + degraded.lastTransitionReason());
        assertTrue(degraded.lastError().contains("boom-2"), "最近错误已记录");
        assertNotNull(degraded.lastErrorAt());

        m.onPollFailure("dev-A", new RuntimeException("boom-3"));
        assertEquals(HealthState.DEGRADED, m.healthOf("dev-A").state(), "未达故障阈值保持降级");

        m.onPollFailure("dev-A", new RuntimeException("boom-4"));
        DeviceHealth fault = m.healthOf("dev-A");
        assertEquals(HealthState.FAULT, fault.state(), "连续 4 次失败判定严重故障");
        assertTrue(fault.lastTransitionReason().contains("故障阈值"),
                "切换原因可解释: " + fault.lastTransitionReason());

        log.info("[健康分级] 反复出错降级路径: {}", transitions.stream()
                .map(h -> h.state() + "<-" + h.lastTransitionReason()).toList());
        assertEquals(List.of(HealthState.DEGRADED, HealthState.FAULT),
                transitions.stream().map(DeviceHealth::state).toList(),
                "切换序列 HEALTHY→DEGRADED→FAULT");
    }

    @Test
    void recoveryObservationMustPassBeforeHealthy() {
        HealthMonitor m = newMonitor();
        for (int i = 0; i < 4; i++) {
            m.onPollFailure("dev-A", new RuntimeException("boom-" + i));
        }
        assertEquals(HealthState.FAULT, m.healthOf("dev-A").state());

        m.onPollSuccess("dev-A");
        DeviceHealth recovering = m.healthOf("dev-A");
        assertEquals(HealthState.RECOVERING, recovering.state(),
                "故障后首次成功只进入恢复观察，不直接回正常");
        assertEquals(1, recovering.consecutiveSuccesses());
        assertEquals(0, recovering.consecutiveFailures());
        assertTrue(recovering.lastTransitionReason().contains("恢复观察"),
                "切换原因可解释: " + recovering.lastTransitionReason());

        m.onPollSuccess("dev-A");
        assertEquals(HealthState.RECOVERING, m.healthOf("dev-A").state(),
                "连续 2 次成功仍在观察期（阈值 3）");

        m.onPollSuccess("dev-A");
        DeviceHealth healthy = m.healthOf("dev-A");
        assertEquals(HealthState.HEALTHY, healthy.state(), "观察期连续 3 次成功才回到正常");
        assertTrue(healthy.lastTransitionReason().contains("恢复观察期"),
                "切换原因可解释: " + healthy.lastTransitionReason());
        log.info("[健康分级] 恢复观察通过后回正常: reason={}", healthy.lastTransitionReason());
    }

    @Test
    void relapseDuringRecoveryGoesBackToDegraded() {
        HealthMonitor m = newMonitor();
        for (int i = 0; i < 4; i++) {
            m.onPollFailure("dev-A", new RuntimeException("boom-" + i));
        }
        m.onPollSuccess("dev-A");
        assertEquals(HealthState.RECOVERING, m.healthOf("dev-A").state());

        m.onPollFailure("dev-A", new RuntimeException("boom-again"));
        DeviceHealth relapsed = m.healthOf("dev-A");
        assertEquals(HealthState.DEGRADED, relapsed.state(), "观察期内复发立即退回降级");
        assertTrue(relapsed.lastTransitionReason().contains("恢复观察期内再次出错"),
                "切换原因可解释: " + relapsed.lastTransitionReason());
        log.info("[健康分级] 观察期复发退回: reason={}", relapsed.lastTransitionReason());
    }

    @Test
    void restoredMonitorContinuesInsteadOfRestarting() {
        HealthMonitor m1 = newMonitor();
        for (int i = 0; i < 3; i++) {
            m1.onPollFailure("dev-A", new RuntimeException("boom-" + i));
        }
        DeviceHealth before = m1.healthOf("dev-A");
        assertEquals(HealthState.DEGRADED, before.state());
        assertEquals(3, before.consecutiveFailures());

        // 模拟重启：从持久化视图恢复，连续失败次数不清零
        HealthMonitor m2 = newMonitor();
        m2.restore(m1.persistedView());
        DeviceHealth restored = m2.healthOf("dev-A");
        assertEquals(before, restored, "重启后健康状态完整衔接");

        m2.onPollFailure("dev-A", new RuntimeException("boom-3"));
        assertEquals(HealthState.FAULT, m2.healthOf("dev-A").state(),
                "续算：再失败 1 次即达故障阈值 4，而不是重新累计");
        log.info("[健康分级] 重启续算: restored={} -> {}", before.state(),
                m2.healthOf("dev-A").state());
    }
}

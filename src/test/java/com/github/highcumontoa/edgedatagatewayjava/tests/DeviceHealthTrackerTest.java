package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthConfig;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthTransition;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealthTracker;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 健康分级状态机：降级阈值、严重故障、恢复观察窗口、观察期再失败、重启续算。 */
class DeviceHealthTrackerTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthTrackerTest.class);

    // 阈值：连续失败 3 降级、5 严重故障；恢复观察需要连续 3 次成功
    private DeviceHealthTracker tracker() {
        return new DeviceHealthTracker("dev-1", new HealthConfig(3, 5, 3));
    }

    @Test
    void repeatedFailuresDegradeThenBecomeCritical() {
        DeviceHealthTracker t = tracker();

        assertNull(t.recordFailure("e1", 10L));
        assertEquals(DeviceHealth.HEALTHY, t.health());
        assertNull(t.recordFailure("e2", 20L));
        // 第 3 次连续失败 → DEGRADED，可查到最近一次切换的原因和时间
        HealthTransition down = t.recordFailure("e3", 30L);
        assertNotNull(down);
        assertEquals(DeviceHealth.HEALTHY, down.from());
        assertEquals(DeviceHealth.DEGRADED, down.to());
        assertEquals(30L, down.atMillis());
        assertTrueContains(down.reason(), "degraded threshold");
        assertEquals(down, t.lastTransition());

        // 第 4 次仍在 DEGRADED；第 5 次 → CRITICAL
        assertNull(t.recordFailure("e4", 40L));
        assertEquals(DeviceHealth.DEGRADED, t.health());
        HealthTransition critical = t.recordFailure("e5", 50L);
        assertNotNull(critical);
        assertEquals(DeviceHealth.CRITICAL, critical.to());
        assertEquals(50L, t.lastTransition().atMillis());
        assertEquals(5, t.consecutiveFailures());
        log.info("[健康分级] 连续失败降级链路: HEALTHY -> {} -> {}, 最近切换={}",
                down.to(), critical.to(), t.lastTransition());
    }

    @Test
    void oneSuccessAfterFailureEntersRecoveryAndOnlyStableRunReturnsHealthy() {
        DeviceHealthTracker t = tracker();
        failN(t, 3);
        assertEquals(DeviceHealth.DEGRADED, t.health());

        // 恢复第 1 次成功：进入 RECOVERING，而不是直接回到 HEALTHY
        HealthTransition enter = t.recordSuccess(100L);
        assertNotNull(enter);
        assertEquals(DeviceHealth.RECOVERING, enter.to());
        // 观察期内成功 2 次仍在观察（避免刚恢复一次就被当成完全正常）
        assertNull(t.recordSuccess(110L));
        assertEquals(DeviceHealth.RECOVERING, t.health());
        // 连续第 3 次成功 → HEALTHY
        HealthTransition healthy = t.recordSuccess(120L);
        assertNotNull(healthy);
        assertEquals(DeviceHealth.HEALTHY, healthy.to());
        assertTrueContains(healthy.reason(), "recovery confirmed");
        log.info("[恢复观察] DEGRADED -> RECOVERING -> HEALTHY, 最后切换原因={}", healthy.reason());
    }

    @Test
    void failureDuringRecoveryObservationRestartsObservation() {
        DeviceHealthTracker t = tracker();
        failN(t, 5);
        assertEquals(DeviceHealth.CRITICAL, t.health());

        assertNotNull(t.recordSuccess(100L));
        assertEquals(DeviceHealth.RECOVERING, t.health());
        assertNull(t.recordSuccess(110L));
        // 观察期（需 3 次成功）内再次失败：观察中止，退回 DEGRADED
        HealthTransition back = t.recordFailure("flap", 120L);
        assertNotNull(back);
        assertEquals(DeviceHealth.RECOVERING, back.from());
        assertEquals(DeviceHealth.DEGRADED, back.to());
        assertEquals(1, t.consecutiveFailures());

        // 再次恢复：连续成功计数重新累积，仍需满 3 次
        assertNotNull(t.recordSuccess(130L));
        assertNull(t.recordSuccess(140L));
        assertEquals(DeviceHealth.RECOVERING, t.health());
        assertNotNull(t.recordSuccess(150L));
        assertEquals(DeviceHealth.HEALTHY, t.health());
        log.info("[恢复观察] 观察期再失败退回 DEGRADED，重新观察后才 HEALTHY");
    }

    @Test
    void criticalAlsoGoesThroughRecoveryObservation() {
        DeviceHealthTracker t = tracker();
        failN(t, 5);
        assertEquals(DeviceHealth.CRITICAL, t.health());
        assertNotNull(t.recordSuccess(1L));
        assertEquals(DeviceHealth.RECOVERING, t.health());
        assertNull(t.recordSuccess(2L));
        assertNotNull(t.recordSuccess(3L));
        assertEquals(DeviceHealth.HEALTHY, t.health());
    }

    @Test
    void restoreKeepsHealthAndStreakBaselineAcrossRestart() {
        DeviceHealthTracker t = tracker();
        failN(t, 4); // DEGRADED，cf=4
        HealthTransition before = t.lastTransition();

        DeviceHealthTracker t2 = tracker();
        t2.restore(t.health(), t.consecutiveFailures(), t.consecutiveSuccesses(),
                t.lastTransition(), t.lastFailureAt(), t.lastSuccessAt());
        assertEquals(DeviceHealth.DEGRADED, t2.health());
        assertEquals(4, t2.consecutiveFailures());
        assertEquals(before, t2.lastTransition());
        // 重启后再失败 1 次即到 CRITICAL（基线衔接，不是从 0 开始）
        assertNotNull(t2.recordFailure("after restart", 200L));
        assertEquals(DeviceHealth.CRITICAL, t2.health());
        log.info("[重启续算] 重启前 cf=4/DEGRADED，重启后再失败 1 次即 CRITICAL（未清零重算）");
    }

    @Test
    void invalidThresholdsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HealthConfig(0, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new HealthConfig(5, 5, 3));
    }

    private static void failN(DeviceHealthTracker t, int n) {
        for (int i = 0; i < n; i++) {
            t.recordFailure("boom-" + i, i + 1L);
        }
    }

    private static void assertTrueContains(String haystack, String needle) {
        assertNotNull(haystack);
        if (!haystack.contains(needle)) {
            throw new AssertionError("expected '" + haystack + "' to contain '" + needle + "'");
        }
    }
}

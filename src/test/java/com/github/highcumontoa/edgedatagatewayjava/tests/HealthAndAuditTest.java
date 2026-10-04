package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.audit.AuditReport;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthState;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsSnapshot;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本轮新增行为的集成测试：设备健康分级、上行中断不误判、恢复观察、
 * 并发下统计核对自洽、重启后续算不重复计数。
 */
class HealthAndAuditTest {

    private static final Logger log = LoggerFactory.getLogger(HealthAndAuditTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);

    @Test
    void repeatedFailuresDegradeDeviceToFault() throws Exception {
        // 设备一直掉线：HEALTHY → DEGRADED → FAULT，阈值 2/10 让中间态可观察
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 20.0, 100L))), false, true);
        EdgeGateway gw = newGateway(root.resolve("degrade"), 100,
                OverflowPolicy.DROP_OLDEST, new MockUplinkChannel(true), List.of(a),
                2, 10, 3);
        gw.start();

        Set<HealthState> seen = new LinkedHashSet<>();
        DemoRunner.await(() -> {
            HealthState s = gw.deviceHealth().get("dev-A").state();
            seen.add(s);
            return s == HealthState.FAULT;
        }, 5000);
        gw.shutdown();

        DeviceHealth h = gw.deviceHealth().get("dev-A");
        log.info("[健康分级] 反复出错降级: 观察到的状态序列={} 最终={} 原因={} 最近错误={}",
                seen, h.state(), h.lastTransitionReason(), h.lastError());
        assertTrue(seen.contains(HealthState.DEGRADED),
                "降级中间态可观察: " + seen);
        assertEquals(HealthState.FAULT, h.state());
        assertTrue(h.consecutiveFailures() >= 10);
        assertNotNull(h.lastTransitionAt(), "最近一次切换时间可查");
        assertTrue(h.lastTransitionReason().contains("故障阈值"), "切换原因可查");
        assertNotNull(h.lastError(), "最近错误可查");
        assertConsistent(gw.audit(), "反复出错场景下核对仍成立");
    }

    @Test
    void uplinkOutageDoesNotMarkDeviceUnhealthy() throws Exception {
        // 上行中断但设备自身轮询一直成功：健康状态必须保持 HEALTHY
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 20.0, ts.addAndGet(10)))), true, true);
        MockUplinkChannel channel = new MockUplinkChannel(false); // 启动即断网
        EdgeGateway gw = newGateway(root.resolve("uplink-down"), 1000,
                OverflowPolicy.DROP_OLDEST, channel, List.of(a), 2, 4, 3);
        gw.start();

        DemoRunner.await(() -> gw.counters().accepted() >= 10, 5000);
        Thread.sleep(300); // 多跑一段时间，确认断网不会侵蚀健康判定
        DeviceHealth h = gw.deviceHealth().get("dev-A");
        log.info("[健康分级] 上行中断不误判: state={} consecutiveFailures={} pending={}",
                h.state(), h.consecutiveFailures(), gw.buffer().size());
        assertEquals(HealthState.HEALTHY, h.state(), "上行中断不得判设备不健康");
        assertEquals(0, h.consecutiveFailures());
        assertTrue(gw.buffer().size() > 0, "断网期间记录本地积压");
        assertConsistent(gw.audit(), "断网积压期间核对成立");

        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();
        assertEquals(HealthState.HEALTHY, gw.deviceHealth().get("dev-A").state());
        assertConsistent(gw.audit(), "恢复补传后核对成立");
    }

    @Test
    void recoveryObservationPassesBeforeBackToHealthy() throws Exception {
        // 先掉线到 FAULT，再恢复：必须先经过 RECOVERING 观察期才回 HEALTHY
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 21.0, ts.addAndGet(10)))), false, true);
        EdgeGateway gw = newGateway(root.resolve("recovery"), 1000,
                OverflowPolicy.DROP_OLDEST, new MockUplinkChannel(true), List.of(a),
                2, 4, 10); // 恢复观察需要连续 10 次成功，观察窗口足够长
        gw.start();
        DemoRunner.await(() -> gw.deviceHealth().get("dev-A").state() == HealthState.FAULT,
                5000);

        a.setOnline(true);
        // 第一次轮询成功后：只能处于恢复观察，不能直接回正常
        DemoRunner.await(() -> gw.counters().snapshot().devices()
                .get("dev-A").okPolls() >= 1, 5000);
        DeviceHealth duringRecovery = gw.deviceHealth().get("dev-A");
        log.info("[健康分级] 故障后首次成功: state={} consecutiveSuccesses={}",
                duringRecovery.state(), duringRecovery.consecutiveSuccesses());
        assertEquals(HealthState.RECOVERING, duringRecovery.state(),
                "刚恢复一次不能被当成完全正常");

        DemoRunner.await(() -> gw.deviceHealth().get("dev-A").state()
                == HealthState.HEALTHY, 5000);
        gw.shutdown();
        DeviceHealth healthy = gw.deviceHealth().get("dev-A");
        log.info("[健康分级] 恢复观察通过: reason={}", healthy.lastTransitionReason());
        assertTrue(healthy.lastTransitionReason().contains("恢复观察期"),
                "回到正常必须经过恢复观察: " + healthy.lastTransitionReason());
        assertConsistent(gw.audit(), "恢复后核对成立");
    }

    @Test
    void auditStaysSelfConsistentUnderConcurrency() throws Exception {
        // 多设备并发采集 + 上行反复断连 + 小缓冲溢出：核对结果不得自相矛盾
        AtomicLong ts = new AtomicLong(1000L);
        List<SimulatedDevice> devices = List.of(
                loopingDevice("dev-A", ts, false),
                loopingDevice("dev-B", ts, true),   // 混入越界读数
                loopingDevice("dev-C", ts, false));
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = newGateway(root.resolve("concurrent"), 30,
                OverflowPolicy.DROP_OLDEST, channel, devices, 2, 4, 3);
        gw.start();

        AtomicBoolean toggling = new AtomicBoolean(true);
        Thread toggler = new Thread(() -> {
            try {
                while (toggling.get()) {
                    channel.disconnect();
                    Thread.sleep(40);
                    channel.reconnect();
                    Thread.sleep(40);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "uplink-toggler");
        toggler.setDaemon(true);
        toggler.start();

        int consistent = 0, unstable = 0;
        for (int i = 0; i < 300; i++) {
            AuditReport report = gw.audit();
            switch (report.status()) {
                case CONSISTENT -> consistent++;
                case UNSTABLE -> unstable++;
                case INCONSISTENT -> throw new AssertionError(
                        "核对结果自相矛盾: " + report.violations());
            }
        }
        toggling.set(false);
        toggler.join(2000);
        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();

        log.info("[统计核对] 并发下核对自洽: 300 次核对 consistent={} unstable={} "
                + "inconsistent=0, counters accepted={} delivered={} dropped={}",
                consistent, unstable, gw.counters().accepted(),
                gw.counters().delivered(), gw.counters().dropped());
        assertTrue(consistent > 0, "并发下应能取得一致性切面");
        assertConsistent(gw.audit(), "停稳后核对成立");
    }

    @Test
    void restartContinuesHealthAndStatsWithoutDoubleCounting() throws Exception {
        Path dir = root.resolve("restart");
        AtomicLong ts = new AtomicLong(1000L);

        // 阶段 1：A 掉线（累计到 FAULT），B 正常采集；上行断网触发溢出丢弃后恢复排空
        ScriptedDevice a1 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 20.0, ts.addAndGet(10)))), false, true);
        ScriptedDevice b1 = new ScriptedDevice("dev-B", List.of(TEMP),
                List.of(() -> List.of(raw("dev-B", 30.0, ts.addAndGet(10)))), true, true);
        MockUplinkChannel ch1 = new MockUplinkChannel(false);
        EdgeGateway gw1 = newGateway(dir, 10, OverflowPolicy.DROP_OLDEST, ch1,
                List.of(a1, b1), 2, 4, 3);
        gw1.start();
        DemoRunner.await(() -> gw1.deviceHealth().get("dev-A").state() == HealthState.FAULT
                && gw1.counters().dropped() >= 1, 5000);
        ch1.reconnect();
        DemoRunner.await(() -> gw1.buffer().size() == 0, 5000);
        gw1.shutdown();

        StatsSnapshot s1 = gw1.counters().snapshot();
        DeviceHealth healthA1 = gw1.deviceHealth().get("dev-A");
        int ch1Delivered = ch1.delivered().size();
        log.info("[重启续算] 阶段1: polled={} accepted={} delivered={} dropped={} "
                + "healthA={} consecFail={}", s1.polled(), s1.accepted(), s1.delivered(),
                s1.dropped(), healthA1.state(), healthA1.consecutiveFailures());
        assertEquals(HealthState.FAULT, healthA1.state());
        assertTrue(s1.dropped() >= 1, "阶段1确有溢出丢弃（续算不能重复计入）");

        // 阶段 2：同目录重启。健康/统计基线衔接重启前；A 恢复上线走恢复观察
        ScriptedDevice a2 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 22.0, ts.addAndGet(10)))), true, true);
        ScriptedDevice b2 = new ScriptedDevice("dev-B", List.of(TEMP),
                List.of(() -> List.of(raw("dev-B", 31.0, ts.addAndGet(10)))), true, true);
        MockUplinkChannel ch2 = new MockUplinkChannel(true);
        EdgeGateway gw2 = newGateway(dir, 10, OverflowPolicy.DROP_OLDEST, ch2,
                List.of(a2, b2), 2, 4, 3);

        StatsSnapshot restored = gw2.counters().snapshot();
        DeviceHealth restoredHealth = gw2.deviceHealth().get("dev-A");
        log.info("[重启续算] 恢复基线: polled={} accepted={} delivered={} dropped={} healthA={}",
                restored.polled(), restored.accepted(), restored.delivered(),
                restored.dropped(), restoredHealth.state());
        assertEquals(s1.polled(), restored.polled(), "轮询计数续算不清零");
        assertEquals(s1.accepted(), restored.accepted(), "接受计数续算不清零");
        assertEquals(s1.delivered(), restored.delivered(), "送达计数续算不清零");
        assertEquals(s1.dropped(), restored.dropped(), "溢出丢弃计数续算不清零");
        assertEquals(healthA1, restoredHealth, "健康状态/连续失败/最近错误完整衔接");

        gw2.start();
        DemoRunner.await(() -> gw2.deviceHealth().get("dev-A").state()
                == HealthState.HEALTHY, 5000);
        DemoRunner.await(() -> gw2.counters().accepted() >= s1.accepted() + 5, 5000);
        gw2.shutdown();

        // 不重复计数：总送达 == 两个通道各自实际收到（已确认数据未被重放）
        int ch2Delivered = ch2.delivered().size();
        StatsSnapshot s2 = gw2.counters().snapshot();
        log.info("[重启续算] 阶段2: delivered={} ch1={} ch2={} accepted={} dropped={}",
                s2.delivered(), ch1Delivered, ch2Delivered, s2.accepted(), s2.dropped());
        assertEquals(ch1Delivered + ch2Delivered, s2.delivered(),
                "重启后送达计数不重复：已确认数据未再次计入");
        assertTrue(s2.accepted() > s1.accepted(), "重启后继续采集");
        assertEquals(HealthState.HEALTHY, gw2.deviceHealth().get("dev-A").state(),
                "恢复观察通过后回到正常");
        assertConsistent(gw2.audit(), "重启后继续运行核对仍成立");
    }

    @Test
    void rejectNewestKeepsSeqAccountingConsistent() throws Exception {
        // REJECT_NEWEST：seq 被消耗形成缺口，序号守恒核对必须依然成立
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 20.0, ts.addAndGet(10)))), true, true);
        MockUplinkChannel channel = new MockUplinkChannel(false);
        EdgeGateway gw = newGateway(root.resolve("reject-newest"), 5,
                OverflowPolicy.REJECT_NEWEST, channel, List.of(a), 2, 4, 3);
        gw.start();

        DemoRunner.await(() -> gw.counters().snapshot().rejectedNewest() >= 3, 5000);
        StatsSnapshot during = gw.counters().snapshot();
        log.info("[统计核对] REJECT_NEWEST: nextSeq={} accepted={} rejectedNewest={}",
                gw.buffer().nextSeq(), during.accepted(), during.rejectedNewest());
        assertEquals(gw.buffer().nextSeq(), during.accepted() + during.rejectedNewest(),
                "已分配序号 == 接受 + 拒新（缺口可解释）");
        assertConsistent(gw.audit(), "拒新期间序号/流向核对成立");

        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();
        assertConsistent(gw.audit(), "补传完成后核对成立");
    }

    // ---- helpers ----

    private static void assertConsistent(AuditReport report, String what) {
        log.info("[统计核对] {}: status={} violations={}", what, report.status(),
                report.violations());
        assertEquals(AuditReport.Status.CONSISTENT, report.status(),
                what + ": " + report.violations());
    }

    private static ScriptedDevice loopingDevice(String id, AtomicLong ts,
                                                boolean withBadReadings) {
        List<Supplier<List<RawReading>>> script = withBadReadings
                ? List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 999.0, ts.addAndGet(10))),  // 越界被拒
                        () -> List.of(raw(id, 21.0, ts.addAndGet(10))))
                : List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 21.0, ts.addAndGet(10))));
        return new ScriptedDevice(id, List.of(TEMP), script, true, true);
    }

    private static EdgeGateway newGateway(Path dir, int capacity, OverflowPolicy policy,
                                          MockUplinkChannel channel,
                                          List<SimulatedDevice> devices,
                                          int degrade, int fault, int recover)
            throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, capacity, policy,
                20L, 20L, 50, degrade, fault, recover), devices, channel);
    }

    private static RawReading raw(String device, Object value, Long ts) {
        return new RawReading(device, "temp", value, "C", ts);
    }
}

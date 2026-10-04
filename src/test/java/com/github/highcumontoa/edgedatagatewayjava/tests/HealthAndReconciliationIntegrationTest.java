package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewaySnapshot;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthConfig;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.stats.ReconciliationReport;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本轮新增能力端到端：
 * 1) 反复出错后降级/严重故障分级；2) 上行中断不误判设备不健康；
 * 3) 恢复观察通过后才回到正常；4) 采集与补传并发下核对结果自洽；
 * 5) 重启后健康与统计基线续算、不重复计数。
 */
class HealthAndReconciliationIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(HealthAndReconciliationIntegrationTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
    private static final PointDefinition PRESSURE =
            new PointDefinition("pressure", DataType.DOUBLE, "kPa", 0.0, 1600.0);

    /** 阈值统一为 3/5/3，采集周期 20ms，等待时间足够区分每一级。 */
    private static EdgeGateway gateway(Path dir, int capacity,
                                       MockUplinkChannel channel,
                                       List<SimulatedDevice> devices) throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, capacity, OverflowPolicy.DROP_OLDEST,
                20L, 20L, 100, new HealthConfig(3, 5, 3)), devices, channel);
    }

    private static RawReading raw(String device, String point, Object value,
                                  String unit, long ts) {
        return new RawReading(device, point, value, unit, ts);
    }

    private static List<Supplier<List<RawReading>>> failuresThenOk(int failures,
                                                                   AtomicLong clock) {
        List<Supplier<List<RawReading>>> script = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            script.add(() -> { throw new IllegalStateException("device boom"); });
        }
        script.add(() -> List.of(raw("dev-A", "temp", 20.0, "C", clock.addAndGet(10))));
        return script;
    }

    @Test
    void repeatedDeviceFailuresDegradeThenBecomeCriticalWithTransitionTrace() throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                failuresThenOk(5, clock), true, false);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = gateway(root.resolve("health-1"), 1000, channel, List.of(a));
        gw.start();

        // 连续第 3 次失败 → DEGRADED
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").errorCount() >= 3, 5000);
        DeviceStats s3 = gw.deviceStats().get("dev-A");
        assertEquals(DeviceHealth.DEGRADED, s3.health(), "连续失败 3 次应降级");
        assertEquals(3, s3.consecutiveFailures());
        assertNotNull(s3.lastHealthTransition());
        log.info("[健康分级] 3 次失败后: health={}, 最近切换={} -> {} at={}, reason={}",
                s3.health(), s3.lastHealthTransition().from(),
                s3.lastHealthTransition().to(), s3.lastHealthTransition().atMillis(),
                s3.lastHealthTransition().reason());

        // 连续第 5 次失败 → CRITICAL
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").errorCount() >= 5, 5000);
        DeviceStats s5 = gw.deviceStats().get("dev-A");
        assertEquals(DeviceHealth.CRITICAL, s5.health(), "连续失败 5 次应判定严重故障");
        assertEquals(DeviceHealth.DEGRADED, s5.lastHealthTransition().from());
        assertEquals(DeviceHealth.CRITICAL, s5.lastHealthTransition().to());
        assertNotNull(s5.lastHealthTransition().reason());
        assertTrue(s5.lastHealthTransition().atMillis() > 0L);
        log.info("[健康分级] 5 次失败后: health={}, lastError={}, 切换原因={}",
                s5.health(), s5.lastError(), s5.lastHealthTransition().reason());
        gw.shutdown();
    }

    @Test
    void uplinkOutageDoesNotMarkHealthyDevicesUnhealthy() throws Exception {
        AtomicLong clock = new AtomicLong(1000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", "temp", 20.0, "C", clock.addAndGet(10)))),
                true, true);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = gateway(root.resolve("health-2"), 1000, channel, List.of(a));
        gw.start();
        DemoRunner.await(() -> gw.counters().accepted() >= 5, 5000);

        // 上行中断 600ms：设备真实轮询持续成功，健康必须保持 HEALTHY
        channel.disconnect();
        Thread.sleep(600);
        DeviceStats during = gw.deviceStats().get("dev-A");
        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);

        assertEquals(DeviceHealth.HEALTHY, during.health(),
                "上行中断不能把设备算成不健康");
        assertEquals(0L, during.errorCount(), "设备自身零失败");
        assertEquals(0, during.consecutiveFailures());
        // 中断期间采集未停，核对仍自洽
        assertTrue(gw.reconcile().consistent(),
                () -> "uplink outage reconciliation issues: " + gw.reconcile().issues());
        log.info("[上行中断不误判] 中断期间设备 health={}, errorCount={}, accepted={}",
                during.health(), during.errorCount(), during.acceptedCount());
        gw.shutdown();
    }

    @Test
    void recoveryObservationMustCompleteBeforeReturningHealthy() throws Exception {
        AtomicLong clock = new AtomicLong(2000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                failuresThenOk(5, clock), true, false);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = gateway(root.resolve("health-3"), 1000, channel, List.of(a));
        gw.start();
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").health()
                == DeviceHealth.CRITICAL, 5000);

        // 脚本耗尽 5 次失败后第 6 批成功：CRITICAL -> RECOVERING
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").health()
                == DeviceHealth.RECOVERING, 5000);
        DeviceStats recovering = gw.deviceStats().get("dev-A");
        assertEquals(1, recovering.consecutiveSuccesses(),
                "刚恢复一次只应进入恢复观察，不能直接正常");
        log.info("[恢复观察] 首次成功后 health={}, cs={}",
                recovering.health(), recovering.consecutiveSuccesses());

        // 恢复观察需连续 3 次成功：脚本 loop=false 只有一批成功，之后都是空批成功，
        // 空批同样是"真实的轮询成功"，因此再等 2 个周期应回到 HEALTHY
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").health()
                == DeviceHealth.HEALTHY, 5000);
        DeviceStats healthy = gw.deviceStats().get("dev-A");
        assertEquals(0, healthy.consecutiveFailures());
        assertNotNull(healthy.lastHealthTransition());
        assertEquals(DeviceHealth.RECOVERING, healthy.lastHealthTransition().from());
        log.info("[恢复观察] 连续成功满窗口后 health={}, 切换原因={}",
                healthy.health(), healthy.lastHealthTransition().reason());
        gw.shutdown();
    }

    @Test
    void reconciliationStaysSelfConsistentUnderConcurrentCollectAndForward() throws Exception {
        AtomicLong clockA = new AtomicLong(3000L);
        AtomicLong clockB = new AtomicLong(6000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", "temp", 21.0, "C", clockA.addAndGet(10)))),
                true, true);
        ScriptedDevice b = new ScriptedDevice("dev-B", List.of(PRESSURE),
                List.of(() -> List.of(raw("dev-B", "pressure", 100.0, "kPa",
                        clockB.addAndGet(10)))), true, true);
        // 容量很小 + 启动即断网：采集与溢出并发进行
        MockUplinkChannel channel = new MockUplinkChannel(false);
        EdgeGateway gw = gateway(root.resolve("health-4"), 4, channel, List.of(a, b));
        gw.start();
        DemoRunner.await(() -> gw.counters().dropped() >= 4, 5000);

        // 一个线程在采集+溢出持续进行时反复核对；每一版报告自身必须自洽
        long deadline = System.currentTimeMillis() + 1500;
        int samples = 0;
        while (System.currentTimeMillis() < deadline) {
            ReconciliationReport r = gw.reconcile();
            assertTrue(r.consistent(),
                    () -> "concurrent report self-contradictory: " + r.issues());
            samples++;
            Thread.sleep(5);
        }
        log.info("[并发核对] 断网积压期间连续 {} 版核对全部自洽（nextSeq={}, dropped={}）",
                samples, gw.buffer().nextSeq(), gw.counters().dropped());

        // 恢复补传，排空期间继续核对
        channel.reconnect();
        long deadline2 = System.currentTimeMillis() + 1500;
        int samples2 = 0;
        while (System.currentTimeMillis() < deadline2 && gw.buffer().size() > 0) {
            ReconciliationReport r = gw.reconcile();
            assertTrue(r.consistent(),
                    () -> "draining report self-contradictory: " + r.issues());
            samples2++;
            Thread.sleep(5);
        }
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        GatewaySnapshot finalSnap = gw.snapshot();
        assertTrue(finalSnap.reconciliation().consistent(),
                () -> finalSnap.reconciliation().issues().toString());
        log.info("[并发核对] 补传排空期间 {} 版核对自洽；终态 nextSeq={} delivered={} droppedOldest={} shed={}",
                samples2, finalSnap.nextSeq(), finalSnap.delivered(),
                finalSnap.reconciliation().globalDroppedOldest(),
                finalSnap.reconciliation().globalShedNewest());
        gw.shutdown();
    }

    @Test
    void restartCarriesOverHealthAndStatsBaselineWithoutDoubleCounting() throws Exception {
        Path dir = root.resolve("health-5");

        // ---- 第一段运行：3 次失败（DEGRADED）+ 若干成功，全部送达后停 ----
        AtomicLong clock1 = new AtomicLong(7000L);
        ScriptedDevice d1 = new ScriptedDevice("dev-A", List.of(TEMP),
                failuresThenOk(3, clock1), true, false);
        MockUplinkChannel ch1 = new MockUplinkChannel(true);
        EdgeGateway gw1 = gateway(dir, 1000, ch1, List.of(d1));
        gw1.start();
        DemoRunner.await(() -> gw1.deviceStats().get("dev-A").health()
                == DeviceHealth.DEGRADED, 5000);
        DemoRunner.await(() -> gw1.counters().accepted() >= 1
                && gw1.counters().delivered() >= 1, 5000);

        // 关停前的运行时状态：3 次失败后 1 次成功 → RECOVERING，cs=1
        DeviceStats beforeStop = gw1.deviceStats().get("dev-A");
        assertEquals(DeviceHealth.RECOVERING, beforeStop.health());
        assertEquals(1, beforeStop.consecutiveSuccesses());
        assertEquals(0, beforeStop.consecutiveFailures());

        gw1.shutdown();
        // 关停窗口可能已落盘额外的空批轮询；以关停后（=重启实际读到的）基线为准
        DeviceStats before = gw1.deviceStats().get("dev-A");
        long acceptedBefore = before.acceptedCount();
        long polledBefore = before.pollCount();
        long deliveredBefore = before.deliveredCount();
        long rejectedBefore = before.rejectedCount();
        long nextSeqBefore = gw1.buffer().nextSeq();
        long confirmedBefore = gw1.buffer().confirmedSeq();
        long downstreamBefore = ch1.delivered().size();
        // 3 次失败后 1 次成功进入 RECOVERING；关停窗口至多再跑一个空批成功（cs+1），
        // 仍未到 HEALTHY（需连续 3 次），因此重启前状态必为 RECOVERING
        assertEquals(DeviceHealth.RECOVERING, before.health());
        assertTrue(before.consecutiveSuccesses() >= 1 && before.consecutiveSuccesses() <= 2,
                "恢复观察中 cs 为 1~2: " + before.consecutiveSuccesses());
        assertEquals(0, before.consecutiveFailures());
        assertTrue(gw1.reconcile().consistent());
        log.info("[重启续算] 重启前: health={}, polled={}, accepted={}, delivered={}, rejected={}, nextSeq={}",
                before.health(), polledBefore, acceptedBefore, deliveredBefore,
                rejectedBefore, nextSeqBefore);

        // ---- 重启：新设备实例、新下游、同数据目录；给足成功批补满观察窗口 ----
        int csBefore = before.consecutiveSuccesses();
        int needed = 3 - csBefore; // 恢复观察还差的连续成功次数
        AtomicLong clock2 = new AtomicLong(8000L);
        List<Supplier<List<RawReading>>> script2 = new ArrayList<>();
        for (int i = 0; i < needed; i++) {
            script2.add(() -> List.of(raw("dev-A", "temp", 22.0, "C", clock2.addAndGet(10))));
        }
        ScriptedDevice d2 = new ScriptedDevice("dev-A", List.of(TEMP), script2, true, false);
        MockUplinkChannel ch2 = new MockUplinkChannel(true);
        EdgeGateway gw2 = gateway(dir, 1000, ch2, List.of(d2));

        // 健康基线衔接：重启瞬间（start 前）恢复观察状态与连续成功次数逐值衔接
        DeviceStats rightAfter = gw2.deviceStats().get("dev-A");
        assertEquals(DeviceHealth.RECOVERING, rightAfter.health(), "健康状态跨重启衔接");
        assertEquals(csBefore, rightAfter.consecutiveSuccesses(), "连续成功次数不清零");
        // 计数基线衔接而非清零（落盘值 == 重启读取值）
        assertEquals(polledBefore, rightAfter.pollCount(), "轮询基线衔接");
        assertEquals(acceptedBefore, rightAfter.acceptedCount(), "接受基线衔接");
        assertEquals(deliveredBefore, rightAfter.deliveredCount(), "送达基线衔接");
        log.info("[重启续算] 重启瞬间: health={}, cs={}, polled={}, accepted={}, delivered={}",
                rightAfter.health(), csBefore, rightAfter.pollCount(),
                rightAfter.acceptedCount(), rightAfter.deliveredCount());

        gw2.start();
        // 观察窗口补满后回到 HEALTHY，本段新增恰好 needed 条接受
        DemoRunner.await(() -> gw2.deviceStats().get("dev-A").health() == DeviceHealth.HEALTHY
                && gw2.counters().accepted() >= acceptedBefore + needed, 5000);
        DemoRunner.await(() -> gw2.counters().delivered() >= deliveredBefore + needed, 5000);
        gw2.shutdown();

        DeviceStats after = gw2.deviceStats().get("dev-A");
        assertEquals(DeviceHealth.HEALTHY, after.health(),
                "重启后稳定采集接续恢复观察并回到正常");
        // 旧数据不重复计数：全局接受数 = 旧基线 + 本段新增
        assertEquals(acceptedBefore + needed, gw2.counters().accepted(),
                "重启后不重复计数，只在旧基线上累加");
        // 新下游只收到重启后的新 seq（旧的已确认记录不重放）
        long newDownstream = ch2.delivered().size();
        assertEquals(needed, newDownstream, "新下游只收到重启后新记录");
        assertTrue(ch2.delivered().stream().allMatch(r -> r.seq() > confirmedBefore),
                "重启后 seq 全部大于旧 confirmedSeq");
        // 重启后继续运行，核对结论仍成立
        assertTrue(gw2.reconcile().consistent(),
                () -> "post-restart reconciliation: " + gw2.reconcile().issues());
        // 跨重启的 seq 去向总账（旧下游 + 新下游 + 丢弃）= 总分配
        assertEquals(gw2.buffer().nextSeq(),
                downstreamBefore + newDownstream + gw2.counters().dropped(),
                "跨重启 seq 守恒：总分配 = 旧下游 + 新下游 + 丢弃");
        log.info("[重启续算] 重启后: health={}, accepted={}（基线 {} + 新增 {}）, 新下游 {} 条, nextSeq={}, 核对自洽={}",
                after.health(), gw2.counters().accepted(), acceptedBefore, needed,
                newDownstream, gw2.buffer().nextSeq(), gw2.reconcile().consistent());
    }
}

package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.audit.AuditReport;
import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.stats.DeviceCounters;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsSnapshot;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计对账一致性：一条读数要么算接受、要么算拒绝；
 * 逐台接受之和 == 整体接受，逐台各拒绝原因之和 == 整体对应原因——
 * 在乱序拒绝、缓冲溢出、断网积压与重启续算之后都成立。
 */
class ReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);

    @Test
    void outOfOrderRejectReconcilesPerDeviceAndGlobal() throws Exception {
        // dev-A 三批：ts=100 接受、ts=90 乱序被拒、ts=110 接受；dev-B 持续正常
        List<Supplier<List<RawReading>>> scriptA = List.of(
                () -> List.of(raw("dev-A", 20.0, 100L)),
                () -> List.of(raw("dev-A", 20.5, 90L)),   // 时间戳回退：网关按乱序拒绝
                () -> List.of(raw("dev-A", 21.0, 110L)));
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP), scriptA, true, false);
        ScriptedDevice b = new ScriptedDevice("dev-B", List.of(TEMP),
                List.of(() -> List.of(raw("dev-B", 30.0, 100L)),
                        () -> List.of(raw("dev-B", 31.0, 110L))), true, false);
        EdgeGateway gw = newGateway(root.resolve("out-of-order"), 1000,
                OverflowPolicy.DROP_OLDEST, new MockUplinkChannel(true), List.of(a, b));
        gw.start();
        DemoRunner.await(() -> gw.counters().snapshot().rejectedByReason()
                        .get(RejectReason.TIMESTAMP_OUT_OF_ORDER) >= 1
                && gw.counters().accepted() >= 4, 5000);
        gw.shutdown();

        StatsSnapshot snap = gw.counters().snapshot();
        DeviceCounters ca = snap.devices().get("dev-A");
        DeviceCounters cb = snap.devices().get("dev-B");
        DeviceStats sa = gw.deviceStats().get("dev-A");
        log.info("[对账] 乱序拒绝: dev-A 注册表 accepted={} rejected={} | 设备视图 accepted={} rejected={} | 整体 accepted={} rejected={}",
                ca.accepted(), ca.rejectedByReason(), sa.acceptedCount(), sa.rejectedCount(),
                snap.accepted(), snap.rejectedByReason());

        // 乱序读数只能算一次：在 dev-A 这里算拒绝（不是接受）
        assertEquals(2, ca.accepted(), "dev-A 注册表接受数不含乱序读数");
        assertEquals(1L, ca.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                "dev-A 自己的拒绝数必须含这条乱序读数");
        // 设备视图（deviceStats）与注册表逐台口径一致：不能在设备侧算接受、整体算拒绝
        assertEquals(ca.accepted(), sa.acceptedCount(),
                "设备视图接受数必须等于注册表逐台接受数");
        assertEquals(ca.rejectedTotal() + ca.rejectedNewest(), sa.rejectedCount(),
                "设备视图拒绝数必须等于注册表逐台拒绝数（含拒新）");
        // 逐台之和 == 整体
        assertReconciles(snap);
        assertConsistent(gw.audit(), "乱序拒绝后逐台与整体对账");
    }

    @Test
    void concurrentCollectAndForwardNeverYieldsContradictoryAudit() throws Exception {
        // 三设备并发采集（dev-B 周期性发乱序读数）+ 小缓冲 DROP_OLDEST 溢出
        // + 上行反复断连：反复核对，任何一份结论都必须自洽
        // 每台设备独立时间戳序列（共享时钟会让“回退 5ms”不一定构成乱序）
        ScriptedDevice a = looping("dev-A", new AtomicLong(1000L), false);
        ScriptedDevice b = looping("dev-B", new AtomicLong(1000L), true);   // 混入乱序读数
        ScriptedDevice c = looping("dev-C", new AtomicLong(1000L), false);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = newGateway(root.resolve("concurrent"), 10,
                OverflowPolicy.DROP_OLDEST, channel, List.of(a, b, c));
        gw.start();

        AtomicBoolean toggling = new AtomicBoolean(true);
        Thread toggler = new Thread(() -> {
            try {
                while (toggling.get()) {
                    channel.disconnect();
                    Thread.sleep(100); // 断网窗口足够长，小缓冲必然触发 DROP_OLDEST
                    channel.reconnect();
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "uplink-toggler");
        toggler.setDaemon(true);
        toggler.start();

        int consistent = 0, unstable = 0;
        for (int i = 0; i < 500; i++) {
            AuditReport report = gw.audit();
            switch (report.status()) {
                case CONSISTENT -> {
                    consistent++;
                    // 结论自洽的进一步验证：同一份报告里逐台之和必须等于整体
                    assertReconciles(report.stats());
                }
                case UNSTABLE -> unstable++;
                case INCONSISTENT -> throw new AssertionError(
                        "核对结论自相矛盾: " + report.violations());
            }
            Thread.sleep(2); // 让采集/补传/断连在核对之间持续推进
        }
        toggling.set(false);
        toggler.join(2000);
        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();

        long outOfOrder = gw.counters().snapshot().rejectedByReason()
                .get(RejectReason.TIMESTAMP_OUT_OF_ORDER);
        log.info("[对账] 并发采集+补传: 500 次核对 consistent={} unstable={} inconsistent=0; "
                + "accepted={} delivered={} dropped={} rejected={} (乱序={})",
                consistent, unstable, gw.counters().accepted(), gw.counters().delivered(),
                gw.counters().dropped(), gw.counters().rejectedTotal(), outOfOrder);
        assertTrue(consistent > 0, "并发下应能取得一致性切面");
        assertTrue(gw.counters().dropped() >= 1, "场景确实覆盖了缓冲溢出丢弃");
        assertTrue(outOfOrder >= 1, "场景确实覆盖了乱序拒绝");
        // 停稳之后：逐台与整体对账必须成立，设备视图与注册表同源一致
        StatsSnapshot snap = gw.counters().snapshot();
        assertReconciles(snap);
        assertDeviceViewMatches(snap, gw.deviceStats());
        assertConsistent(gw.audit(), "停稳后逐台与整体对账");
    }

    @Test
    void restartWithBacklogReconcilesBeforeAndAfterRecovery() throws Exception {
        // 阶段 1：断网积压 + DROP_OLDEST 溢出 + 乱序拒绝，未排空直接优雅停机
        Path dir = root.resolve("restart-backlog");
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a1 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 20.0, ts.addAndGet(10))),
                        () -> List.of(raw("dev-A", 20.5, ts.get() - 5)),  // 乱序被拒
                        () -> List.of(raw("dev-A", 21.0, ts.addAndGet(10)))),
                true, true);
        MockUplinkChannel ch1 = new MockUplinkChannel(false); // 全程断网
        EdgeGateway gw1 = newGateway(dir, 10, OverflowPolicy.DROP_OLDEST, ch1, List.of(a1));
        gw1.start();
        DemoRunner.await(() -> gw1.counters().dropped() >= 1
                && gw1.counters().snapshot().rejectedByReason()
                        .get(RejectReason.TIMESTAMP_OUT_OF_ORDER) >= 1, 5000);
        gw1.shutdown();

        StatsSnapshot s1 = gw1.counters().snapshot();
        int backlog = gw1.buffer().size();
        log.info("[对账] 重启前: accepted={} delivered={} dropped={} rejected={} backlog={}",
                s1.accepted(), s1.delivered(), s1.dropped(), s1.rejectedTotal(), backlog);
        assertTrue(backlog > 0, "停机时仍有未确认积压");
        assertTrue(s1.dropped() >= 1, "阶段1确有溢出丢弃");
        assertReconciles(s1);
        assertDeviceViewMatches(s1, gw1.deviceStats());
        assertConsistent(gw1.audit(), "停机前（断网积压+溢出+乱序）对账");

        // 阶段 2：同目录重启，上行仍断。逐台基线与整体不得错位，积压未排空时核对即成立
        ScriptedDevice a2 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", 22.0, ts.addAndGet(10)))), true, true);
        MockUplinkChannel ch2 = new MockUplinkChannel(false);
        EdgeGateway gw2 = newGateway(dir, 10, OverflowPolicy.DROP_OLDEST, ch2, List.of(a2));

        StatsSnapshot restored = gw2.counters().snapshot();
        DeviceCounters ra = restored.devices().get("dev-A");
        DeviceCounters sa = s1.devices().get("dev-A");
        log.info("[对账] 重启续算基线: dev-A accepted {} -> {}, delivered {} -> {}, "
                + "droppedOldest {} -> {}, 整体 accepted={}",
                sa.accepted(), ra.accepted(), sa.delivered(), ra.delivered(),
                sa.droppedOldest(), ra.droppedOldest(), restored.accepted());
        assertEquals(sa.accepted(), ra.accepted(), "逐台接受基线衔接");
        assertEquals(sa.delivered(), ra.delivered(), "逐台送达基线衔接");
        assertEquals(sa.droppedOldest(), ra.droppedOldest(), "逐台弃旧基线衔接");
        assertEquals(sa.rejectedByReason(), ra.rejectedByReason(), "逐台拒绝原因基线衔接");
        assertReconciles(restored);
        assertDeviceViewMatches(restored, gw2.deviceStats());
        assertConsistent(gw2.audit(), "重启后（积压未排空）逐台与整体对账");

        // 阶段 3：继续采集 + 恢复上行排空，对账持续成立且不重复计数
        gw2.start();
        ch2.reconnect();
        DemoRunner.await(() -> gw2.buffer().size() == 0
                && gw2.counters().accepted() >= s1.accepted() + 3, 5000);
        gw2.shutdown();

        StatsSnapshot s2 = gw2.counters().snapshot();
        log.info("[对账] 恢复补传后: accepted={} delivered={} ch1={} ch2={}",
                s2.accepted(), s2.delivered(), ch1.delivered().size(),
                ch2.delivered().size());
        assertEquals(ch1.delivered().size() + ch2.delivered().size(), s2.delivered(),
                "重启后送达不重复：已确认数据未再次计入");
        assertReconciles(s2);
        assertDeviceViewMatches(s2, gw2.deviceStats());
        assertConsistent(gw2.audit(), "恢复补传后逐台与整体对账");
    }

    // ---- helpers ----

    private static ScriptedDevice looping(String id, AtomicLong ts, boolean outOfOrder) {
        List<Supplier<List<RawReading>>> script = outOfOrder
                ? List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 20.5, ts.get() - 5)),  // 乱序：回退 5ms
                        () -> List.of(raw(id, 21.0, ts.addAndGet(10))))
                : List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 21.0, ts.addAndGet(10))));
        return new ScriptedDevice(id, List.of(TEMP), script, true, true);
    }

    /** 设备视图（deviceStats）与注册表逐台计数同源一致。 */
    static void assertDeviceViewMatches(StatsSnapshot snap, Map<String, DeviceStats> view) {
        for (DeviceCounters c : snap.devices().values()) {
            DeviceStats v = view.get(c.deviceId());
            assertTrue(v != null, "设备视图缺少 " + c.deviceId());
            assertEquals(c.accepted(), v.acceptedCount(),
                    c.deviceId() + " 设备视图接受数与注册表不一致");
            assertEquals(c.rejectedTotal() + c.rejectedNewest(), v.rejectedCount(),
                    c.deviceId() + " 设备视图拒绝数与注册表不一致");
            assertEquals(c.polls(), v.pollCount(),
                    c.deviceId() + " 设备视图轮询数与注册表不一致");
        }
    }

    static void assertReconciles(StatsSnapshot snap) {
        long sumAccepted = 0, sumDelivered = 0, sumDroppedOldest = 0, sumRejectedNewest = 0;
        Map<RejectReason, Long> sumRejected = new java.util.EnumMap<>(RejectReason.class);
        for (RejectReason r : RejectReason.values()) {
            sumRejected.put(r, 0L);
        }
        for (DeviceCounters d : snap.devices().values()) {
            sumAccepted += d.accepted();
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumRejectedNewest += d.rejectedNewest();
            d.rejectedByReason().forEach((r, n) -> sumRejected.merge(r, n, Long::sum));
        }
        assertEquals(sumAccepted, snap.accepted(), "逐台接受之和必须等于整体接受");
        assertEquals(sumDelivered, snap.delivered(), "逐台送达之和必须等于整体送达");
        assertEquals(sumDroppedOldest, snap.droppedOldest(), "逐台弃旧之和必须等于整体弃旧");
        assertEquals(sumRejectedNewest, snap.rejectedNewest(), "逐台拒新之和必须等于整体拒新");
        for (RejectReason r : RejectReason.values()) {
            assertEquals(sumRejected.get(r),
                    snap.rejectedByReason().getOrDefault(r, 0L),
                    "逐台拒绝原因 " + r + " 之和必须等于整体对应原因");
        }
    }

    static void assertConsistent(AuditReport report, String what) {
        log.info("[对账] {}: status={} violations={}", what, report.status(),
                report.violations());
        assertEquals(AuditReport.Status.CONSISTENT, report.status(),
                what + ": " + report.violations());
    }

    static EdgeGateway newGateway(Path dir, int capacity, OverflowPolicy policy,
                                  MockUplinkChannel channel,
                                  List<SimulatedDevice> devices) throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, capacity, policy,
                20L, 20L, 50, 2, 4, 3), devices, channel);
    }

    private static RawReading raw(String device, Object value, Long ts) {
        return new RawReading(device, "temp", value, "C", ts);
    }
}

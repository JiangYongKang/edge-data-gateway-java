package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.audit.AuditReport;
import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferView;
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
 * 统计一致性对账：逐台统计与整体统计口径一致、并发下核对结论自洽、
 * 断网积压/溢出丢弃/乱序拒绝/重启续算之后对账关系仍然成立。
 */
class StatsReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(StatsReconciliationTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);

    @Test
    void outOfOrderRejectReconcilesPerDeviceAndGlobal() throws Exception {
        // 一台设备：3 条接受 + 1 条时间戳回退被拒。该读数必须在逐台与整体
        // 两侧都计为拒绝（TIMESTAMP_OUT_OF_ORDER），且不计入任何一侧的接受数。
        List<Supplier<List<RawReading>>> script = List.of(
                () -> List.of(raw("dev-A", 20.0, 100L)),
                () -> List.of(raw("dev-A", 21.0, 105L)),
                () -> List.of(raw("dev-A", 22.0, 90L)),   // 时间戳回退：乱序拒绝
                () -> List.of(raw("dev-A", 23.0, 110L)));
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP), script, true, false);
        EdgeGateway gw = newGateway(root.resolve("out-of-order"), 100,
                OverflowPolicy.DROP_OLDEST, new MockUplinkChannel(true), List.of(a));
        gw.start();

        DemoRunner.await(() -> gw.counters().accepted() >= 3
                        && gw.counters().snapshot().devices()
                        .get("dev-A").rejectedByReason()
                        .getOrDefault(RejectReason.TIMESTAMP_OUT_OF_ORDER, 0L) == 1
                        && gw.buffer().size() == 0, 5000);
        gw.shutdown();

        StatsSnapshot s = gw.counters().snapshot();
        DeviceCounters d = s.devices().get("dev-A");
        DeviceStats ds = gw.deviceStats().get("dev-A");
        log.info("[对账] 乱序拒绝: 设备 accepted={} rejected[OOO]={} | 整体 accepted={} "
                        + "rejected[OOO]={} | DeviceStats accepted={} rejected={}",
                d.accepted(), d.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                s.accepted(), s.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                ds.acceptedCount(), ds.rejectedCount());

        assertEquals(3, d.accepted(), "乱序读数不得计入逐台接受数");
        assertEquals(3, s.accepted(), "乱序读数不得计入整体接受数");
        assertEquals(1, d.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                "乱序读数必须计入逐台拒绝数");
        assertEquals(1, s.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                "乱序读数必须计入整体拒绝数");
        assertReconciled(gw, "乱序拒绝后逐台与整体对账");
        assertConsistent(gw.audit(), "乱序拒绝后核对成立");
    }

    @Test
    void rejectNewestReconcilesPerDeviceAndGlobal() throws Exception {
        // 断网 + REJECT_NEWEST：缓冲满后新读数被拒，逐台拒新数必须等于整体拒新数
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a = loopingDevice("dev-A", ts, false);
        MockUplinkChannel channel = new MockUplinkChannel(false);
        EdgeGateway gw = newGateway(root.resolve("reject-newest"), 3,
                OverflowPolicy.REJECT_NEWEST, channel, List.of(a));
        gw.start();

        DemoRunner.await(() -> gw.counters().snapshot().rejectedNewest() >= 3, 5000);
        StatsSnapshot during = gw.counters().snapshot();
        DeviceStats dsDuring = gw.deviceStats().get("dev-A");
        DeviceCounters dDuring = during.devices().get("dev-A");
        log.info("[对账] 缓冲拒新(断网中): 设备 rejectedNewest={} 整体={} "
                        + "DeviceStats.droppedNewest={}",
                dDuring.rejectedNewest(), during.rejectedNewest(),
                dsDuring.droppedNewestCount());
        assertEquals(dDuring.rejectedNewest(), during.rejectedNewest(),
                "逐台拒新数必须等于整体拒新数");
        assertConsistent(gw.audit(), "断网拒新期间核对成立");

        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();
        assertReconciled(gw, "拒新恢复后逐台与整体对账");
        assertConsistent(gw.audit(), "拒新恢复后核对成立");
    }

    @Test
    void concurrentAuditsNeverContradictThemselves() throws Exception {
        // 多设备并发采集（混入越界 + 乱序读数）+ 上行反复断连 + 小缓冲溢出丢弃：
        // 每一份核对结论都必须自洽——CONSISTENT（且报告内部数字互相吻合）或
        // UNSTABLE（明确无法判定），绝不出现 INCONSISTENT 或自相矛盾的报告。
        AtomicLong ts = new AtomicLong(1000L);
        List<SimulatedDevice> devices = List.of(
                loopingDevice("dev-A", ts, true),
                loopingDevice("dev-B", ts, true),
                loopingDevice("dev-C", ts, false));
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = newGateway(root.resolve("concurrent"), 5,
                OverflowPolicy.DROP_OLDEST, channel, devices);
        gw.start();

        AtomicBoolean toggling = new AtomicBoolean(true);
        Thread toggler = new Thread(() -> {
            try {
                while (toggling.get()) {
                    channel.disconnect();
                    Thread.sleep(60);
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
        for (int i = 0; i < 400; i++) {
            AuditReport report = gw.audit();
            switch (report.status()) {
                case CONSISTENT -> {
                    consistent++;
                    assertReportSelfConsistent(report);
                }
                case UNSTABLE -> unstable++;
                case INCONSISTENT -> throw new AssertionError(
                        "并发下核对出自相矛盾的结论: " + report.violations());
            }
        }
        toggling.set(false);
        toggler.join(2000);
        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();

        StatsSnapshot s = gw.counters().snapshot();
        log.info("[对账] 并发核对自洽: 400 次核对 consistent={} unstable={} "
                        + "inconsistent=0 | accepted={} delivered={} dropped={} "
                        + "rejected[OOO]={} rejected[RANGE]={}",
                consistent, unstable, s.accepted(), s.delivered(), s.dropped(),
                s.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                s.rejectedByReason().get(RejectReason.OUT_OF_RANGE));
        assertTrue(consistent > 0, "并发下应能取得一致性切面");
        assertTrue(s.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER) > 0,
                "场景必须确实产生过乱序拒绝");
        assertTrue(s.dropped() > 0, "场景必须确实产生过溢出丢弃");
        assertReconciled(gw, "并发停稳后逐台与整体对账");
        assertConsistent(gw.audit(), "并发停稳后核对成立");
    }

    @Test
    void restartKeepsPerDeviceAndGlobalAligned() throws Exception {
        // 阶段 1：接受 + 乱序拒绝 + 越界拒绝 + 断网积压触发 DROP_OLDEST 后恢复排空
        Path dir = root.resolve("restart");
        AtomicLong ts = new AtomicLong(1000L);
        ScriptedDevice a1 = loopingDevice("dev-A", ts, true);
        ScriptedDevice b1 = loopingDevice("dev-B", ts, false);
        MockUplinkChannel ch1 = new MockUplinkChannel(false);
        EdgeGateway gw1 = newGateway(dir, 8, OverflowPolicy.DROP_OLDEST, ch1,
                List.of(a1, b1));
        gw1.start();
        DemoRunner.await(() -> gw1.counters().dropped() >= 1
                && gw1.counters().snapshot().rejectedByReason()
                        .get(RejectReason.TIMESTAMP_OUT_OF_ORDER) >= 1, 5000);
        ch1.reconnect();
        DemoRunner.await(() -> gw1.buffer().size() == 0, 5000);
        gw1.shutdown();

        StatsSnapshot s1 = gw1.counters().snapshot();
        log.info("[对账] 重启前: accepted={} delivered={} dropped={} rejected[OOO]={} "
                        + "rejected[RANGE]={}",
                s1.accepted(), s1.delivered(), s1.dropped(),
                s1.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                s1.rejectedByReason().get(RejectReason.OUT_OF_RANGE));
        assertTrue(s1.dropped() >= 1 && s1.rejectedTotal() >= 1,
                "阶段1必须同时覆盖溢出丢弃与拒绝");
        assertReconciled(gw1, "重启前逐台与整体对账");

        // 阶段 2：同目录重启，逐台基线与整体不得错位；继续跑（含乱序拒绝）后仍对得上
        ScriptedDevice a2 = loopingDevice("dev-A", ts, true);
        ScriptedDevice b2 = loopingDevice("dev-B", ts, false);
        EdgeGateway gw2 = newGateway(dir, 8, OverflowPolicy.DROP_OLDEST,
                new MockUplinkChannel(true), List.of(a2, b2));

        StatsSnapshot restored = gw2.counters().snapshot();
        log.info("[对账] 重启恢复基线: accepted={} delivered={} dropped={} rejected={}",
                restored.accepted(), restored.delivered(), restored.dropped(),
                restored.rejectedTotal());
        assertEquals(s1.accepted(), restored.accepted(), "整体接受基线续算不清零");
        assertEquals(s1.rejectedTotal(), restored.rejectedTotal(), "整体拒绝基线续算不清零");
        assertEquals(s1.dropped(), restored.dropped(), "整体丢弃基线续算不清零");
        assertBaselineAligned(restored, "重启后逐台基线与整体");

        gw2.start();
        DemoRunner.await(() -> gw2.counters().accepted() >= s1.accepted() + 10
                && gw2.buffer().size() == 0, 5000);
        gw2.shutdown();

        StatsSnapshot s2 = gw2.counters().snapshot();
        log.info("[对账] 重启续跑后: accepted={} delivered={} dropped={} rejected[OOO]={}",
                s2.accepted(), s2.delivered(), s2.dropped(),
                s2.rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER));
        assertTrue(s2.accepted() > s1.accepted(), "重启后继续采集");
        assertReconciled(gw2, "重启续跑后逐台与整体对账");
        assertConsistent(gw2.audit(), "重启续跑后核对成立");
    }

    // ---- helpers ----

    /** 停稳后的全量对账：逐台 DeviceStats、逐台 DeviceCounters 与整体三方口径一致。 */
    private static void assertReconciled(EdgeGateway gw, String what) {
        StatsSnapshot s = gw.counters().snapshot();
        Map<String, DeviceStats> deviceStats = gw.deviceStats();
        long sumAccepted = 0, sumDelivered = 0, sumDroppedOldest = 0,
                sumRejectedNewest = 0, sumPolled = 0;
        Map<RejectReason, Long> sumRejected = new java.util.EnumMap<>(RejectReason.class);
        for (RejectReason r : RejectReason.values()) {
            sumRejected.put(r, 0L);
        }
        for (DeviceCounters d : s.devices().values()) {
            DeviceStats ds = deviceStats.get(d.deviceId());
            assertEquals(d.accepted(), ds.acceptedCount(),
                    what + ": " + d.deviceId() + " 采集侧接受数与统计侧不一致");
            assertEquals(d.rejectedTotal(), ds.rejectedCount(),
                    what + ": " + d.deviceId() + " 采集侧拒绝数与统计侧不一致");
            assertEquals(d.rejectedNewest(), ds.droppedNewestCount(),
                    what + ": " + d.deviceId() + " 采集侧拒新数与统计侧不一致");
            sumAccepted += d.accepted();
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumRejectedNewest += d.rejectedNewest();
            sumPolled += d.polls();
            d.rejectedByReason().forEach((r, n) -> sumRejected.merge(r, n, Long::sum));
        }
        assertEquals(sumAccepted, s.accepted(), what + ": 整体接受数 != 逐台之和");
        assertEquals(sumDelivered, s.delivered(), what + ": 整体送达数 != 逐台之和");
        assertEquals(sumDroppedOldest, s.droppedOldest(), what + ": 整体挤弃数 != 逐台之和");
        assertEquals(sumRejectedNewest, s.rejectedNewest(), what + ": 整体拒新数 != 逐台之和");
        assertEquals(sumPolled, s.polled(), what + ": 整体轮询数 != 逐台之和");
        for (RejectReason r : RejectReason.values()) {
            assertEquals(sumRejected.get(r),
                    s.rejectedByReason().getOrDefault(r, 0L),
                    what + ": 整体拒绝[" + r + "] != 逐台之和");
        }
        log.info("[对账] {}: 通过 (accepted={} delivered={} dropped={} rejected={})",
                what, s.accepted(), s.delivered(), s.dropped(), s.rejectedTotal());
    }

    /** 重启恢复出的基线本身必须自洽：整体 == 逐台之和。 */
    private static void assertBaselineAligned(StatsSnapshot s, String what) {
        long sumAccepted = 0, sumDelivered = 0, sumDroppedOldest = 0,
                sumRejectedNewest = 0, sumPolled = 0;
        Map<RejectReason, Long> sumRejected = new java.util.EnumMap<>(RejectReason.class);
        for (RejectReason r : RejectReason.values()) {
            sumRejected.put(r, 0L);
        }
        for (DeviceCounters d : s.devices().values()) {
            sumAccepted += d.accepted();
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumRejectedNewest += d.rejectedNewest();
            sumPolled += d.polls();
            d.rejectedByReason().forEach((r, n) -> sumRejected.merge(r, n, Long::sum));
        }
        assertEquals(sumAccepted, s.accepted(), what + ": accepted 错位");
        assertEquals(sumDelivered, s.delivered(), what + ": delivered 错位");
        assertEquals(sumDroppedOldest, s.droppedOldest(), what + ": droppedOldest 错位");
        assertEquals(sumRejectedNewest, s.rejectedNewest(), what + ": rejectedNewest 错位");
        assertEquals(sumPolled, s.polled(), what + ": polled 错位");
        for (RejectReason r : RejectReason.values()) {
            assertEquals(sumRejected.get(r), s.rejectedByReason().getOrDefault(r, 0L),
                    what + ": rejected[" + r + "] 错位");
        }
    }

    /** 一份 CONSISTENT 报告的内部数字必须互相吻合（用报告自己的快照与缓冲视图复核）。 */
    private static void assertReportSelfConsistent(AuditReport report) {
        StatsSnapshot s = report.stats();
        BufferView v = report.buffer();
        long sumAccepted = 0, sumDelivered = 0, sumDroppedOldest = 0,
                sumRejectedNewest = 0;
        for (DeviceCounters d : s.devices().values()) {
            sumAccepted += d.accepted();
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumRejectedNewest += d.rejectedNewest();
        }
        assertEquals(sumAccepted, s.accepted(), "报告内部: 整体接受 != 逐台之和");
        assertEquals(sumDelivered, s.delivered(), "报告内部: 整体送达 != 逐台之和");
        assertEquals(sumDroppedOldest, s.droppedOldest(), "报告内部: 整体挤弃 != 逐台之和");
        assertEquals(sumRejectedNewest, s.rejectedNewest(), "报告内部: 整体拒新 != 逐台之和");
        assertEquals(s.accepted() + s.rejectedNewest(), v.nextSeq(),
                "报告内部: 序号不守恒");
        assertEquals(s.delivered() + s.droppedOldest() + v.pending(), s.accepted(),
                "报告内部: 流向不守恒");
    }

    private static void assertConsistent(AuditReport report, String what) {
        log.info("[对账] {}: status={} violations={}", what, report.status(),
                report.violations());
        assertEquals(AuditReport.Status.CONSISTENT, report.status(),
                what + ": " + report.violations());
    }

    /**
     * 循环产出读数的设备；withBadReadings=true 时周期性混入越界读数与时间戳
     * 回退的乱序读数（两种拒绝都必须在对账中有所体现）。
     */
    private static ScriptedDevice loopingDevice(String id, AtomicLong ts,
                                                boolean withBadReadings) {
        List<Supplier<List<RawReading>>> script = withBadReadings
                ? List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 999.0, ts.addAndGet(10))),   // 越界拒绝
                        () -> List.of(raw(id, 21.0, ts.get() - 1000)),     // 时间戳回退：乱序拒绝
                        () -> List.of(raw(id, 22.0, ts.addAndGet(10))))
                : List.of(() -> List.of(raw(id, 20.0, ts.addAndGet(10))),
                        () -> List.of(raw(id, 21.0, ts.addAndGet(10))));
        return new ScriptedDevice(id, List.of(TEMP), script, true, true);
    }

    private static EdgeGateway newGateway(Path dir, int capacity, OverflowPolicy policy,
                                          MockUplinkChannel channel,
                                          List<SimulatedDevice> devices) throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, capacity, policy,
                20L, 20L, 50, 2, 4, 3), devices, channel);
    }

    private static RawReading raw(String device, Object value, Long ts) {
        return new RawReading(device, "temp", value, "C", ts);
    }
}

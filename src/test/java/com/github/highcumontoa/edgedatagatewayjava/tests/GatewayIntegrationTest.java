package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端：多设备并发、故障隔离、乱序/畸形拒绝、溢出边界、重启位点一致。 */
class GatewayIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayIntegrationTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
    private static final PointDefinition PRESSURE =
            new PointDefinition("pressure", DataType.DOUBLE, "kPa", 0.0, 1600.0);

    @Test
    void oneDeviceFailureDoesNotAffectOthersAndStatsReflectReality() throws Exception {
        // 设备 A 三批：正常 / 抛异常 / 恢复正常
        List<Supplier<List<RawReading>>> scriptA = List.of(
                () -> List.of(raw("dev-A", "temp", 20.0, "C", 100L)),
                () -> { throw new IllegalStateException("device A boom"); },
                () -> List.of(raw("dev-A", "temp", 21.0, "C", 101L)));
        // 设备 B 持续正常
        List<Supplier<List<RawReading>>> scriptB = List.of(
                () -> List.of(raw("dev-B", "pressure", 100.0, "kPa", 200L)),
                () -> List.of(raw("dev-B", "pressure", 101.0, "kPa", 201L)),
                () -> List.of(raw("dev-B", "pressure", 102.0, "kPa", 202L)));

        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP), scriptA, true, false);
        ScriptedDevice b = new ScriptedDevice("dev-B", List.of(PRESSURE), scriptB, true, true);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = newGatewayGateway(root.resolve("case1"), 1000, channel, List.of(a, b));
        gw.start();

        // A 耗尽三批；B 循环至少三批
        DemoRunner.await(() -> gw.deviceStats().get("dev-A").pollCount() >= 3
                && gw.deviceStats().get("dev-B").acceptedCount() >= 3, 5000);
        gw.shutdown();

        DeviceStats sa = gw.deviceStats().get("dev-A");
        DeviceStats sb = gw.deviceStats().get("dev-B");
        log.info("[设备状态] A={}", sa);
        log.info("[设备状态] B={}", sb);

        assertEquals(1, sa.errorCount(), "A 的异常被记录");
        assertTrue(sa.acceptedCount() >= 2, "A 故障前后都采集成功: " + sa.acceptedCount());
        assertEquals(0, sb.errorCount(), "B 不受 A 故障影响");
        assertTrue(sb.acceptedCount() >= 3, "B 持续采集: " + sb.acceptedCount());

        // 下游：A 与 B 的记录都在，seq 全局单调、升序无重复
        List<NormalizedRecord> delivered = channel.delivered();
        long last = -1;
        for (NormalizedRecord r : delivered) {
            assertTrue(r.seq() > last, "downstream seqs strictly ascending");
            last = r.seq();
        }
        assertTrue(delivered.stream().anyMatch(r -> "dev-A".equals(r.deviceId())));
        assertTrue(delivered.stream().anyMatch(r -> "dev-B".equals(r.deviceId())));
        log.info("[补传位点] all delivered: {}",
                delivered.stream().map(r -> r.seq() + ":" + r.deviceId()).toList());
    }

    @Test
    void malformedAndOutOfOrderReadingsRejectedWithDistinctReasons() throws Exception {
        List<Supplier<List<RawReading>>> script = List.of(
                () -> List.of(raw("dev-A", "temp", 20.0, "C", 100L)),   // ok
                () -> List.of(raw("dev-A", "temp", 999.0, "C", 110L)),  // 越界
                () -> List.of(raw("dev-A", "temp", 20.0, "F", 120L)),   // 单位错
                () -> List.of(raw("dev-A", "temp", null, "C", 130L)),   // 空值
                () -> List.of(raw("dev-A", "temp", 20.0, "C", 90L)),    // 时间戳回退
                () -> List.of(raw("dev-A", "temp", 22.0, "C", 140L)));  // ok（相等允许，回退拒绝）
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP), script, true, false);
        MockUplinkChannel channel = new MockUplinkChannel(true);
        EdgeGateway gw = newGatewayGateway(root.resolve("case2"), 1000, channel, List.of(a));
        gw.start();

        DemoRunner.await(() -> gw.counters().rejectedTotal() >= 4
                && gw.counters().accepted() >= 2, 5000);
        gw.shutdown();

        var reasons = gw.counters().rejectedByReason();
        log.info("[拒绝统计] {}", reasons);
        assertEquals(1L, reasons.get(RejectReason.OUT_OF_RANGE));
        assertEquals(1L, reasons.get(RejectReason.UNIT_MISMATCH));
        assertEquals(1L, reasons.get(RejectReason.VALUE_NULL));
        assertEquals(1L, reasons.get(RejectReason.TIMESTAMP_OUT_OF_ORDER));
        // 脏值绝不到下游
        assertEquals(2, channel.delivered().size());
        List<Double> values = channel.delivered().stream()
                .map(r -> (Double) r.value()).toList();
        assertEquals(List.of(20.0, 22.0), values, "脏值被拒，仅合法值到达下游");
        log.info("[补传位点] downstream seqs={}, values={}",
                channel.delivered().stream().map(NormalizedRecord::seq).toList(), values);
    }

    @Test
    void bufferOverflowDuringOutageDropsOldestAndReplaysRest() throws Exception {
        // 循环产出合法读数；断网期间缓冲只有 5，确定丢弃最旧记录
        List<Supplier<List<RawReading>>> script = List.of(
                () -> List.of(raw("dev-A", "temp", 1.0, "C", 1000L)),
                () -> List.of(raw("dev-A", "temp", 2.0, "C", 1010L)),
                () -> List.of(raw("dev-A", "temp", 3.0, "C", 1020L)));
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP), script, true, true);
        MockUplinkChannel channel = new MockUplinkChannel(false); // 启动即断网
        EdgeGateway gw = newGatewayGateway(root.resolve("case3"), 5, channel, List.of(a));
        gw.start();

        // 等待产生超过容量的记录并触发 DROP_OLDEST
        DemoRunner.await(() -> gw.counters().dropped() >= 1 && gw.buffer().nextSeq() >= 8, 5000);
        long nextAtReconnect = gw.buffer().nextSeq();
        assertEquals(5L, gw.buffer().size(), "buffer holds at most capacity");
        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();

        // 下游 = 重放的 5 条（seq 最大的 5 个），严格升序、无重复、无被丢弃记录
        List<NormalizedRecord> delivered = channel.delivered();
        assertEquals(5, delivered.size());
        List<Long> seqs = delivered.stream().map(NormalizedRecord::seq).toList();
        assertEquals(List.of(nextAtReconnect - 5, nextAtReconnect - 4, nextAtReconnect - 3,
                nextAtReconnect - 2, nextAtReconnect - 1), seqs);
        // 已分配 seq 数 = 下游条数 + 溢出丢弃条数（计数守恒，可解释）
        assertEquals(nextAtReconnect, (long) delivered.size() + gw.counters().dropped(),
                "seq accounting: allocated = delivered + dropped");
        log.info("[补传位点] overflow replay: nextAtReconnect={} deliveredSeqs={} droppedCounter={}",
                nextAtReconnect, seqs, gw.counters().dropped());
    }

    @Test
    void restartContinuesAtConfirmedPositionAndKeepsOnlineStatsConsistent() throws Exception {
        Path dir = root.resolve("case4");
        List<Supplier<List<RawReading>>> script = List.of(
                () -> List.of(raw("dev-A", "temp", 1.0, "C", 100L)),
                () -> List.of(raw("dev-A", "temp", 2.0, "C", 110L)),
                () -> List.of(raw("dev-A", "temp", 3.0, "C", 120L)));
        ScriptedDevice device = new ScriptedDevice("dev-A", List.of(TEMP), script, true, false);
        MockUplinkChannel ch1 = new MockUplinkChannel(true);
        EdgeGateway gw1 = newGatewayGateway(dir, 1000, ch1, List.of(device));
        gw1.start();
        DemoRunner.await(() -> gw1.buffer().size() == 0 && gw1.counters().accepted() == 3, 5000);
        gw1.shutdown();
        long confirmed = gw1.buffer().confirmedSeq();
        long nextSeq = gw1.buffer().nextSeq();
        assertEquals(2L, confirmed);
        assertEquals(3L, nextSeq);
        log.info("[补传位点] before restart: confirmedSeq={} nextSeq={}", confirmed, nextSeq);

        // 用新设备实例（重启后内存清零）+ 新下游，验证只续传未确认的新数据，已确认的不重放
        ScriptedDevice device2 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", "temp", 4.0, "C", 130L))), true, false);
        MockUplinkChannel ch2 = new MockUplinkChannel(true);
        EdgeGateway gw2 = newGatewayGateway(dir, 1000, ch2, List.of(device2));
        assertEquals(nextSeq, gw2.buffer().nextSeq(), "nextSeq restored");
        assertEquals(confirmed, gw2.buffer().confirmedSeq(), "confirmedSeq restored");
        gw2.start();
        DemoRunner.await(() -> gw2.buffer().confirmedSeq() == 3L, 5000);
        gw2.shutdown();

        // ch2 只收到 seq=3 一条；seq 0..2 未被重放（已确认不丢不重）
        List<Long> seqs2 = ch2.delivered().stream().map(NormalizedRecord::seq).toList();
        assertEquals(List.of(3L), seqs2, "no replay of confirmed records after restart");
        // 乱序时间戳状态也恢复：130 > 120 才被接受；先发个回退读数确认状态
        log.info("[补传位点] after restart delivered={}, snapshot confirmedSeq={}",
                seqs2, gw2.buffer().confirmedSeq());
    }

    private static EdgeGateway newGatewayGateway(Path dir, int capacity,
                                                 MockUplinkChannel channel,
                                                 List<SimulatedDevice> devices) throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, capacity, OverflowPolicy.DROP_OLDEST,
                20L, 20L, 100), devices, channel);
    }

    @Test
    void lastTimestampRestoredAfterRestartRejectsBackwardReadings() throws Exception {
        Path dir = root.resolve("case5");
        ScriptedDevice d1 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", "temp", 20.0, "C", 500L))), true, false);
        MockUplinkChannel ch1 = new MockUplinkChannel(true);
        EdgeGateway gw1 = newGatewayGateway(dir, 1000, ch1, List.of(d1));
        gw1.start();
        DemoRunner.await(() -> gw1.counters().accepted() == 1, 5000);
        gw1.shutdown();
        assertEquals(1, ch1.delivered().size());

        // 重启后，旧于最后接受时间戳 500 的读数必须被拒绝（乱序位点持久化生效）
        ScriptedDevice d2 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(raw("dev-A", "temp", 21.0, "C", 499L)),
                        () -> List.of(raw("dev-A", "temp", 22.0, "C", 501L))),
                true, false);
        MockUplinkChannel ch2 = new MockUplinkChannel(true);
        EdgeGateway gw2 = newGatewayGateway(dir, 1000, ch2, List.of(d2));
        gw2.start();
        DemoRunner.await(() -> gw2.counters().rejectedTotal() >= 1
                && ch2.delivered().size() == 1, 5000);
        gw2.shutdown();

        assertEquals(1L,
                gw2.counters().rejectedByReason().get(RejectReason.TIMESTAMP_OUT_OF_ORDER),
                "backward reading after restart rejected");
        List<Long> ts = ch2.delivered().stream().map(NormalizedRecord::timestamp).toList();
        assertEquals(List.of(501L), ts);
        log.info("[补传位点] timestamp position restored after restart, deliveredTs={}", ts);
    }

    private static RawReading raw(String device, String point, Object value,
                                  String unit, Long ts) {
        return new RawReading(device, point, value, unit, ts);
    }
}

package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REJECT_NEWEST 溢出：shedNewest 缺口口径下，seq 分配与去向守恒仍对得上。 */
class RejectNewestReconciliationTest {

    private static final Logger log = LoggerFactory.getLogger(RejectNewestReconciliationTest.class);

    @TempDir
    Path root;

    @Test
    void rejectNewestGapsStillReconcileWithSeqAccounting() throws Exception {
        PointDefinition temp = new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
        AtomicLong clock = new AtomicLong(9000L);
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(temp),
                List.of((Supplier<List<RawReading>>) () ->
                        List.of(new RawReading("dev-A", "temp", 21.0, "C",
                                clock.addAndGet(10)))), true, true);
        MockUplinkChannel channel = new MockUplinkChannel(false); // 启动即断网
        GatewayProperties props = new GatewayProperties(root.resolve("shed").toFile().toPath()
                .resolve("d"), 3, OverflowPolicy.REJECT_NEWEST, 20L, 20L, 100,
                HealthConfig.defaults());
        EdgeGateway gw = new EdgeGateway(props, List.<SimulatedDevice>of(a), channel);
        gw.start();

        // 容量 3：前 3 条入缓冲，之后全部 REJECT_NEWEST（seq 消耗、shedNewest 计数）
        DemoRunner.await(() -> gw.counters().shedNewest() >= 4, 5000);
        ReconciliationReport during = gw.reconcile();
        assertTrue(during.consistent(), () -> "shed issues: " + during.issues());
        log.info("[REJECT-NEWEST 核对] 断网积压: nextSeq={} accepted={} shed={} pending={} consistent={}",
                during.nextSeq(), during.globalAccepted(), during.globalShedNewest(),
                during.globalPending(), during.consistent());

        channel.reconnect();
        DemoRunner.await(() -> gw.buffer().size() == 0, 5000);
        gw.shutdown();

        ReconciliationReport after = gw.reconcile();
        assertTrue(after.consistent(), () -> "post-drain issues: " + after.issues());
        // nextSeq = accepted + shedNewest；accepted 全部送达（DROP_OLDEST=0, pending=0）
        assertEquals(gw.buffer().nextSeq(),
                after.globalAccepted() + after.globalShedNewest(),
                "allocated = accepted + shedNewest");
        assertEquals(after.globalAccepted(),
                after.globalDelivered() + after.globalDroppedOldest() + after.globalPending(),
                "accepted = delivered + droppedOldest + pending");
        log.info("[REJECT-NEWEST 核对] 补传后: nextSeq={} delivered={} shed={} consistent={}",
                after.nextSeq(), after.globalDelivered(), after.globalShedNewest(),
                after.consistent());
    }
}

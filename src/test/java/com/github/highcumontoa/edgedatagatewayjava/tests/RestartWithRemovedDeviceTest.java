package com.github.highcumontoa.edgedatagatewayjava.tests;

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
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移除设备后重启：该设备不再采集，但其 WAL 未确认记录与计数基线仍被恢复，
 * 补传可继续送达，全局 seq 守恒与逐设备核对仍然成立。
 */
class RestartWithRemovedDeviceTest {

    private static final Logger log = LoggerFactory.getLogger(RestartWithRemovedDeviceTest.class);

    @TempDir
    Path root;

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);

    private static EdgeGateway gw(Path dir, MockUplinkChannel ch, List<SimulatedDevice> devs)
            throws Exception {
        return new EdgeGateway(new GatewayProperties(dir, 1000, OverflowPolicy.DROP_OLDEST,
                20L, 20L, 100), devs, ch);
    }

    @Test
    void removedDevicePendingRecordsStillDeliverAndReconcileAfterRestart() throws Exception {
        Path dir = root.resolve("removed");

        // 第一段：两设备采集，断网使记录留在缓冲（未确认），随后停网关
        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(new RawReading("dev-A", "temp", 20.0, "C", 100L))),
                true, false);
        ScriptedDevice b = new ScriptedDevice("dev-B", List.of(TEMP),
                List.of(() -> List.of(new RawReading("dev-B", "temp", 21.0, "C", 200L))),
                true, false);
        MockUplinkChannel ch1 = new MockUplinkChannel(false);
        EdgeGateway gw1 = gw(dir, ch1, List.of(a, b));
        gw1.start();
        DemoRunner.await(() -> gw1.buffer().size() == 2, 5000);
        gw1.shutdown();
        assertEquals(2, gw1.buffer().nextSeq());

        // 重启：设备列表只保留 dev-A（dev-B 被移除），通道恢复
        ScriptedDevice a2 = new ScriptedDevice("dev-A", List.of(TEMP),
                List.of(() -> List.of(new RawReading("dev-A", "temp", 22.0, "C", 300L))),
                true, false);
        MockUplinkChannel ch2 = new MockUplinkChannel(true);
        EdgeGateway gw2 = gw(dir, ch2, List.of(a2));
        gw2.start();
        DemoRunner.await(() -> gw2.buffer().size() == 0, 5000);
        gw2.shutdown();

        // dev-B 的历史未确认记录仍被补传送达（按 seq 幂等）
        List<NormalizedRecord> delivered = ch2.delivered();
        assertEquals(3, delivered.size(), "dev-A 新记录 + 两条历史未确认记录（含 dev-B）");
        assertTrue(delivered.stream().anyMatch(r -> "dev-B".equals(r.deviceId())),
                "removed device's pending record still delivered");
        // dev-B 基线仍参与核对：其 accepted=1, delivered=1；核对整体自洽
        assertTrue(gw2.reconcile().consistent(),
                () -> "reconciliation after device removal: " + gw2.reconcile().issues());
        assertTrue(gw2.deviceStats().containsKey("dev-B"), "removed device baseline retained");
        assertEquals(1L, gw2.deviceStats().get("dev-B").acceptedCount());
        assertEquals(1L, gw2.deviceStats().get("dev-B").deliveredCount());
        // nextSeq 接续：seq 0,1 历史 + seq 2 新记录
        assertEquals(3L, gw2.buffer().nextSeq());
        log.info("[移除设备重启] 下游设备={}, dev-B 基线 accepted={}/delivered={}, 核对自洽={}",
                delivered.stream().map(r -> r.seq() + ":" + r.deviceId()).toList(),
                gw2.deviceStats().get("dev-B").acceptedCount(),
                gw2.deviceStats().get("dev-B").deliveredCount(),
                gw2.reconcile().consistent());
    }
}

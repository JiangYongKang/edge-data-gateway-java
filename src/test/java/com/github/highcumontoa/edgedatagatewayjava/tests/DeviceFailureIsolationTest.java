package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.device.MockDevice;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单设备故障隔离：
 * 一台设备 poll 始终抛异常（在线状态应转 false、错误被记录），
 * 另一台正常设备的采集与缓冲必须完全不受影响。
 * 通过真实的周期调度器并发运行来验证隔离性。
 */
class DeviceFailureIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(DeviceFailureIsolationTest.class);

    /** poll 永远抛异常的故障设备。 */
    private static final class BrokenDevice implements Device {
        @Override
        public String deviceId() {
            return "broken";
        }

        @Override
        public Map<String, PointDefinition> points() {
            return Map.of();
        }

        @Override
        public List<RawReading> poll(long offset, int maxBatch) {
            throw new IllegalStateException("simulated device bus failure");
        }
    }

    private Device healthyDevice() {
        PointDefinition p = new PointDefinition("p", DataType.DOUBLE, "C", 0.0, 100.0);
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            script.add(new MockDevice.ScriptEntry(
                    new RawReading("healthy", "p", (double) i, "C", 1000L + i)));
        }
        return new MockDevice("healthy", Map.of("p", p), script);
    }

    @Test
    void brokenDeviceDoesNotStopHealthyDevice(@TempDir Path dir) throws Exception {
        List<Device> devices = List.of(new BrokenDevice(), healthyDevice());
        try (GatewayHarness gw = new GatewayHarness(dir, 1000, devices)) {
            // 使用真实周期调度：间隔 30ms
            gw.props.setCollectIntervalMs(30);
            gw.collector.bind(devices, 5, 30);
            gw.collector.startAll();

            // 让周期任务并发跑一段时间
            Thread.sleep(1500);

            DeviceStats broken = gw.collector.stats().get("broken");
            DeviceStats healthy = gw.collector.stats().get("healthy");

            log.info("[ISOLATION] broken online={} errors={} lastError={} | healthy online={} accepted={} offset={}",
                    broken.isOnline(), broken.consecutiveErrors(), broken.lastError(),
                    healthy.isOnline(), healthy.acceptedCount(), healthy.offset());

            // 故障设备：离线、累积错误、位点不动
            assertFalse(broken.isOnline());
            assertTrue(broken.consecutiveErrors() > 0);
            assertTrue(broken.lastError().contains("simulated device bus failure"));
            assertEquals(0, broken.offset());

            // 健康设备：完全不受影响，40 条全部采集成功
            assertTrue(healthy.isOnline());
            assertEquals(40, healthy.offset());
            assertEquals(40, healthy.acceptedCount());
            assertEquals(0, healthy.rejectedCount());
            assertEquals(40, gw.spool.pendingCount());

            gw.collector.stopAll();
        }
    }
}

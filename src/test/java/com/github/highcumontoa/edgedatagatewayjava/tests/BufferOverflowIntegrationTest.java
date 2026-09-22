package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.device.MockDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓冲溢出边界：
 * <ul>
 *   <li>达到容量上限后采用“拒绝新写入、保留最旧未确认数据”的确定策略；</li>
 *   <li>背压期间位点停在未入缓冲的读数上，恢复补传腾出空间后继续，数据不丢不重；</li>
 *   <li>统计中的 backpressure 次数与实际一致。</li>
 * </ul>
 */
class BufferOverflowIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BufferOverflowIntegrationTest.class);

    /** 构造一台全部读数合法的设备，共 total 条，时间戳严格递增。 */
    private Device monotonicDevice(int total) {
        PointDefinition p = new PointDefinition("p", DataType.DOUBLE, "C", 0.0, 100.0);
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            script.add(new MockDevice.ScriptEntry(
                    new RawReading("dev", "p", (double) i, "C", 1000L + i)));
        }
        return new MockDevice("dev", Map.of("p", p), script);
    }

    @Test
    void fullBufferAppliesBackpressureThenResumesWithoutLoss(@TempDir Path dir) {
        final int total = 25;
        final int capacity = 5;
        List<Device> devices = List.of(monotonicDevice(total));

        try (GatewayHarness gw = new GatewayHarness(dir, capacity, devices)) {
            gw.channel.disconnect();
            DeviceStats stats = gw.collector.stats().get("dev");

            // 反复采集直到缓冲打满且背压出现（设备无法再推进）
            int attempts = 0;
            while (gw.spool.pendingCount() < capacity && attempts < 1000) {
                gw.collector.collectOnce("dev");
                attempts++;
            }
            // 再采若干轮：位点必须停在 capacity，不再增长
            for (int i = 0; i < 5; i++) {
                long advanced = gw.collector.collectOnce("dev");
                assertEquals(0, advanced, "缓冲满时位点不得推进");
            }
            log.info("[OVERFLOW] spool full pending={}/{}, offset={}, backpressureEvents={}",
                    gw.spool.pendingCount(), capacity, stats.offset(), stats.spoolBackpressureCount());

            assertEquals(capacity, gw.spool.pendingCount());
            assertEquals(capacity, stats.offset(), "已入缓冲的条数恰好等于容量");
            assertTrue(stats.spoolBackpressureCount() >= 5,
                    "每次尝试都应记录一次背压事件");

            // 缓冲内最旧 seq=0（保留最旧数据，不覆盖、不丢弃）
            assertEquals(0L, gw.spool.oldestPendingSeq());

            // 恢复通道并补传：每次 flush 腾出空间后继续采集，直到耗尽
            gw.channel.reconnect();
            int guard = 0;
            while ((gw.spool.pendingCount() > 0 || stats.offset() < total) && guard < 1000) {
                gw.replay.flushOnce();
                gw.collector.collectOnce("dev");
                guard++;
            }
            gw.replay.flushOnce();

            log.info("[OVERFLOW-RESUMED] offset={}, pending={}, delivered={}, suppressed={}",
                    stats.offset(), gw.spool.pendingCount(),
                    gw.channel.deliveredCount(), gw.channel.duplicateSuppressed());

            assertEquals(total, stats.offset(), "全部读数位点均已消费");
            assertEquals(0, gw.spool.pendingCount(), "最终缓冲清空");
            assertEquals(total, gw.channel.deliveredCount(), "下游记录数等于全部读数，无丢无重");
            assertEquals(0, gw.channel.duplicateSuppressed());
        }
    }
}

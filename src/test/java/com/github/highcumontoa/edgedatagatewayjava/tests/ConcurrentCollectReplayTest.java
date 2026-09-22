package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.device.MockDevice;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多设备并发采集 + 独立补传线程并发运行：
 * 全局序列号不重不缺、缓冲确认与下游记录一致、无数据丢失。
 * 中途注入断网/恢复，使补传经历重试。
 */
class ConcurrentCollectReplayTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCollectReplayTest.class);

    private Device device(String id, int total, long baseTs) {
        PointDefinition p = new PointDefinition("p", DataType.DOUBLE, "C", 0.0, 10_000.0);
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            script.add(new MockDevice.ScriptEntry(
                    new RawReading(id, "p", (double) i, "C", baseTs + i)));
        }
        return new MockDevice(id, Map.of("p", p), script);
    }

    @Test
    void concurrentCollectionAndReplayStayConsistent(@TempDir Path dir) throws Exception {
        List<Device> devices = List.of(
                device("d1", 300, 1_000_000L),
                device("d2", 250, 2_000_000L),
                device("d3", 200, 3_000_000L));
        int total = 300 + 250 + 200;

        try (GatewayHarness gw = new GatewayHarness(dir, total, devices)) {
            gw.collector.bind(devices, 7, 5);
            gw.collector.startAll();
            gw.replay.start(5);

            // 运行中途断网 300ms，再恢复
            Thread.sleep(150);
            gw.channel.disconnect();
            log.info("[CONCUR] channel disconnected mid-run, pending={}", gw.spool.pendingCount());
            Thread.sleep(300);
            gw.channel.reconnect();
            log.info("[CONCUR] channel reconnected, pending={}", gw.spool.pendingCount());

            // 等待全部采集完成（位点到达脚本末尾）
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                long offSum = devices.stream()
                        .mapToLong(d -> gw.collector.stats().get(d.deviceId()).offset())
                        .sum();
                if (offSum >= total) {
                    break;
                }
                Thread.sleep(20);
            }
            // 等待缓冲全部补传确认
            deadline = System.currentTimeMillis() + 10_000;
            while (gw.spool.pendingCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            gw.collector.stopAll();
            gw.replay.stop();

            List<NormalizedRecord> delivered = gw.channel.deliveredRecords();
            long seqCount = delivered.stream().map(NormalizedRecord::seq).distinct().count();
            log.info("[CONCUR-RESULT] total={} delivered={} distinctSeq={} nextSeq={} pending={}",
                    total, delivered.size(), seqCount, gw.spool.nextSeq(), gw.spool.pendingCount());

            assertEquals(0, gw.spool.pendingCount());
            assertEquals(total, delivered.size(), "下游记录数等于全部采集读数");
            assertEquals(total, seqCount, "下游 seq 无重复");
            // seq 从 0 连续到 total-1（不重不缺）
            for (int i = 0; i < total; i++) {
                final int idx = i;
                assertTrue(delivered.stream().anyMatch(r -> r.seq() == idx),
                        "缺失 seq=" + idx);
            }
            // 每个设备位点正确、无拒绝
            devices.forEach(d -> {
                var s = gw.collector.stats().get(d.deviceId());
                assertEquals(0, s.rejectedCount());
                assertEquals(0, s.consecutiveErrors());
            });
        }
    }
}

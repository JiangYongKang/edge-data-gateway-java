package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.device.DemoDeviceFactory;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 畸形读数拒绝原因可区分、位点推进、重启后状态一致：
 * 重启后从同一目录恢复，已采集数据不重复、位点不回退、拒绝日志可追溯。
 */
class MalformedAndRestartIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MalformedAndRestartIntegrationTest.class);

    @Test
    void rejectReasonsAreDistinctAndPersisted(@TempDir Path dir) {
        List<Device> devices = DemoDeviceFactory.create();
        try (GatewayHarness gw = new GatewayHarness(dir, 1000, devices)) {
            gw.channel.disconnect();
            gw.drainAll();

            List<RejectedReading> rejections = gw.rejectionLog.loadAll();
            Map<RejectReason, Long> count = new EnumMap<>(RejectReason.class);
            for (RejectedReading rr : rejections) {
                count.merge(rr.reason(), 1L, Long::sum);
                log.warn("[REJECTED-READING] reason={} raw={} detail={}",
                        rr.reason(), rr.raw(), rr.detail());
            }
            log.info("[REJECT-SUMMARY] {}", count);

            // 七类原因在演示脚本中均被触发
            for (RejectReason reason : RejectReason.values()) {
                assertTrue(count.getOrDefault(reason, 0L) >= 1,
                        "应至少触发一次拒绝原因: " + reason + "，实际: " + count);
            }
            // 全部畸形读数均落日志：A=7（七类原因各一），B=2（两条越界）
            assertEquals(9, rejections.size());

            // 每条拒绝都带有原始读数与可解释 detail，不静默丢弃
            for (RejectedReading rr : rejections) {
                assertTrue(rr.detail() != null && !rr.detail().isBlank());
                assertTrue(rr.raw() != null);
            }

            // 设备统计与拒绝日志一致
            DeviceStats statsA = gw.collector.stats().get("device-A");
            DeviceStats statsB = gw.collector.stats().get("device-B");
            assertEquals(7, statsA.rejectedCount());
            assertEquals(2, statsB.rejectedCount());
            assertEquals(12, statsA.offset());
            assertEquals(18, statsB.offset());
            Map<RejectReason, Long> reasonsA = statsA.rejectReasonsSnapshot();
            assertEquals(1, reasonsA.get(RejectReason.UNKNOWN_POINT));
            assertEquals(1, reasonsA.get(RejectReason.TIMESTAMP_OUT_OF_ORDER));
        }
    }

    @Test
    void restartResumesFromPersistedOffsetWithoutDuplicate(@TempDir Path dir) {
        List<Device> devices = DemoDeviceFactory.create();
        int downstreamAfterFirstRun;
        long nextSeqAfterFirstRun;
        int pendingAfterFirstRun;

        // —— 第一次运行：在线，采集并补传全部数据 ——
        try (GatewayHarness gw = new GatewayHarness(dir, 1000, devices)) {
            gw.drainAll();
            int rounds = 0;
            while (gw.spool.pendingCount() > 0 && rounds < 100) {
                gw.replay.flushOnce();
                rounds++;
            }
            pendingAfterFirstRun = gw.spool.pendingCount();
            downstreamAfterFirstRun = gw.channel.deliveredCount();
            nextSeqAfterFirstRun = gw.spool.nextSeq();
            log.info("[RUN1] pending={} delivered={} nextSeq={} offsetA={} offsetB={}",
                    pendingAfterFirstRun, downstreamAfterFirstRun, nextSeqAfterFirstRun,
                    gw.checkpoints.loadOffset("device-A"),
                    gw.checkpoints.loadOffset("device-B"));
        }

        // —— 第二次运行：全新 harness（模拟重启），下游换用全新内存通道 ——
        try (GatewayHarness gw2 = new GatewayHarness(dir, 1000, devices)) {
            // 位点已恢复：脚本已耗尽，采集不再产生新数据
            gw2.drainAll();
            int acceptedAfterRestart = gw2.collector.stats().values().stream()
                    .mapToInt(s -> (int) s.acceptedCount()).sum();
            int rejectedAfterRestart = gw2.collector.stats().values().stream()
                    .mapToInt(s -> (int) s.rejectedCount()).sum();
            log.info("[RUN2] newAccepted={} newRejected={} spoolPendingRecovered={} nextSeq={}",
                    acceptedAfterRestart, rejectedAfterRestart,
                    gw2.spool.pendingCount(), gw2.spool.nextSeq());

            assertEquals(12, gw2.checkpoints.loadOffset("device-A"));
            assertEquals(18, gw2.checkpoints.loadOffset("device-B"));
            assertEquals(0, acceptedAfterRestart, "重启后不得从旧位点重复采集");
            assertEquals(0, rejectedAfterRestart);
            // 缓冲已全部确认：恢复后为空；seq 位点持久化保持
            assertEquals(0, gw2.spool.pendingCount());
            assertEquals(nextSeqAfterFirstRun, gw2.spool.nextSeq());
            // 新下游未收到任何数据（无未确认残留，也不重复投递）
            assertEquals(0, gw2.channel.deliveredCount());
        }
    }
}

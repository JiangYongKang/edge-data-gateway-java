package com.github.highcumontoa.edgedatagatewayjava.tests;

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

/**
 * 断网期间“进程崩溃重启”：
 * 未确认记录在 spool 文件中、位点在 checkpoint 文件中，
 * 全新进程恢复后继续采集剩余数据并在通道恢复后补传全部记录，不丢不重。
 */
class RecoveryWhileOfflineIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryWhileOfflineIntegrationTest.class);

    private Device device(int total) {
        PointDefinition p = new PointDefinition("p", DataType.DOUBLE, "C", 0.0, 100.0);
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            script.add(new MockDevice.ScriptEntry(
                    new RawReading("dev", "p", (double) i, "C", 1000L + i)));
        }
        return new MockDevice("dev", Map.of("p", p), script);
    }

    @Test
    void crashWhileOfflineThenRestartAndReplay(@TempDir Path dir) {
        final int total = 30;
        List<Device> devices = List.of(device(total));

        // 第一次运行：断网，只采集前若干条后“崩溃”（直接关闭，不 flush）
        try (GatewayHarness gw = new GatewayHarness(dir, 1000, devices)) {
            gw.channel.disconnect();
            for (int i = 0; i < 4; i++) {
                gw.collector.collectOnce("dev"); // batch=5 × 4 = 20 条
            }
            log.info("[CRASH] offset={} pending={}",
                    gw.checkpoints.loadOffset("dev"), gw.spool.pendingCount());
            assertEquals(20, gw.spool.pendingCount());
            // 不做任何清理即离开 try-with-resources（stop 仅停线程，不删持久数据）
        }

        // 全新进程恢复：先仍断网，应恢复 20 条缓冲且位点=20
        try (GatewayHarness gw2 = new GatewayHarness(dir, 1000, List.of(device(total)))) {
            assertEquals(20, gw2.spool.pendingCount());
            assertEquals(20, gw2.checkpoints.loadOffset("dev"));
            assertEquals(0L, gw2.spool.pendingSorted(100).get(0).seq(),
                    "恢复后补传必须从最旧位点 seq=0 开始");

            gw2.channel.disconnect();
            gw2.drainAll(); // 采集剩余 10 条
            assertEquals(total, gw2.spool.pendingCount());
            log.info("[RESTART-OFFLINE] resumed pending={} offset={}, cursor oldestSeq={}",
                    gw2.spool.pendingCount(), gw2.checkpoints.loadOffset("dev"),
                    gw2.spool.oldestPendingSeq());

            // 通道恢复，补传全部
            gw2.channel.reconnect();
            int rounds = 0;
            while (gw2.spool.pendingCount() > 0 && rounds < 100) {
                gw2.replay.flushOnce();
                rounds++;
            }
            log.info("[RESTART-REPLAY] delivered={} pending={}",
                    gw2.channel.deliveredCount(), gw2.spool.pendingCount());
            assertEquals(0, gw2.spool.pendingCount());
            assertEquals(total, gw2.channel.deliveredCount(), "跨崩溃数据不丢不重");
            // 下游 seq 恰好 0..29
            var seqs = gw2.channel.deliveredRecords().stream()
                    .map(r -> r.seq()).sorted().toList();
            for (int i = 0; i < total; i++) {
                assertEquals(i, seqs.get(i));
            }
        }
    }
}

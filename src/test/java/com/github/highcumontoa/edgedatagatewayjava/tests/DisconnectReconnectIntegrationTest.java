package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.device.DemoDeviceFactory;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断网 → 采集缓冲 → 恢复补传 的端到端一致性：
 * 补传按 seq 升序、下游无重复、缓冲最终清空、确认位点可观测。
 */
class DisconnectReconnectIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DisconnectReconnectIntegrationTest.class);

    @Test
    void bufferWhileOfflineThenReplayInOrderAfterReconnect(@TempDir Path dir) {
        List<Device> devices = DemoDeviceFactory.create();
        try (GatewayHarness gw = new GatewayHarness(dir, 1000, devices)) {
            // 1) 断网后采集：数据应全部进入本地缓冲，下游为空
            gw.channel.disconnect();
            gw.drainAll();

            int pendingWhileOffline = gw.spool.pendingCount();
            log.info("[OFFLINE-COLLECT] pending in spool={} nextSeq={} delivered={}",
                    pendingWhileOffline, gw.spool.nextSeq(), gw.channel.deliveredCount());
            assertTrue(pendingWhileOffline > 0, "断网期间应有数据进入缓冲");
            assertEquals(0, gw.channel.deliveredCount());

            // 断网期间 flush 不应改变缓冲
            int ackedWhileOffline = gw.replay.flushOnce();
            assertEquals(0, ackedWhileOffline);
            assertEquals(pendingWhileOffline, gw.spool.pendingCount());

            // 2) 恢复连接并补传
            gw.channel.reconnect();
            int totalAcked = 0;
            int rounds = 0;
            while (gw.spool.pendingCount() > 0 && rounds < 100) {
                totalAcked += gw.replay.flushOnce();
                rounds++;
            }
            log.info("[REPLAY-DONE] rounds={} acked={} pending={} delivered={}",
                    rounds, totalAcked, gw.spool.pendingCount(), gw.channel.deliveredCount());

            assertEquals(0, gw.spool.pendingCount());
            assertEquals(pendingWhileOffline, totalAcked);
            // 本场景无自然键重复：缓冲确认数等于下游唯一记录数
            assertEquals(pendingWhileOffline, gw.channel.deliveredCount());
            assertEquals(0, gw.channel.duplicateSuppressed());

            // 3) 下游记录按 seq 严格升序，且无重复 seq
            List<NormalizedRecord> delivered = gw.channel.deliveredRecords();
            long lastSeq = -1;
            for (NormalizedRecord r : delivered) {
                assertTrue(r.seq() > lastSeq, "下游记录必须按 seq 升序");
                lastSeq = r.seq();
            }
            assertEquals(delivered.size(),
                    delivered.stream().map(NormalizedRecord::seq).distinct().count(),
                    "下游不得有重复 seq");
            log.info("[REPLAY-ORDER] downstream seqs={}",
                    delivered.stream().map(NormalizedRecord::seq).toList());
        }
    }
}

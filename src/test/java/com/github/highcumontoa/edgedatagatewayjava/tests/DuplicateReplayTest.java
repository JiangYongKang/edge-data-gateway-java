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
 * 重复投递 / 重复确认的幂等性：
 * <ol>
 *   <li>正常补传成功后再次 flush（缓冲已空）——下游无变化；</li>
 *   <li>手动对同一批 seq 重复 acknowledge ——缓冲无副作用；</li>
 *   <li>通道在线但瞬时失败后重试同一批——下游只出现一次。</li>
 * </ol>
 */
class DuplicateReplayTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateReplayTest.class);

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
    void repeatedFlushAndAckAreIdempotent(@TempDir Path dir) {
        List<Device> devices = List.of(device(10));
        try (GatewayHarness gw = new GatewayHarness(dir, 100, devices)) {
            gw.drainAll();
            assertEquals(10, gw.spool.pendingCount());

            int acked1 = gw.replay.flushOnce();
            log.info("[DUP-REPLAY] first flush acked={} delivered={} pending={}",
                    acked1, gw.channel.deliveredCount(), gw.spool.pendingCount());
            assertEquals(10, acked1);
            assertEquals(10, gw.channel.deliveredCount());
            assertEquals(0, gw.spool.pendingCount());

            // 重复 flush：无数据可投
            int acked2 = gw.replay.flushOnce();
            assertEquals(0, acked2);
            assertEquals(10, gw.channel.deliveredCount());

            // 重复确认历史 seq：幂等无副作用
            gw.spool.acknowledge(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));
            gw.spool.acknowledge(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L));
            gw.spool.acknowledge(List.of(12345L));
            assertEquals(0, gw.spool.pendingCount());
            assertEquals(10, gw.channel.deliveredCount());
            assertEquals(10L, gw.spool.nextSeq());
        }
    }

    @Test
    void transientFailureThenRetryDeliversExactlyOnce(@TempDir Path dir) {
        List<Device> devices = List.of(device(6));
        try (GatewayHarness gw = new GatewayHarness(dir, 100, devices)) {
            gw.drainAll();
            // 前两次交付失败（在线状态仍为 true），第三次成功
            gw.channel.injectTransientFailures(2);
            int a = gw.replay.flushOnce();
            int b = gw.replay.flushOnce();
            int c = gw.replay.flushOnce();
            log.info("[DUP-RETRY] flush acked: {}, {}, {}; delivered={}, pending={}",
                    a, b, c, gw.channel.deliveredCount(), gw.spool.pendingCount());
            assertEquals(0, a);
            assertEquals(0, b);
            assertEquals(6, c);
            assertEquals(6, gw.channel.deliveredCount(), "失败重试不得产生重复记录");
            assertEquals(0, gw.spool.pendingCount());
        }
    }
}

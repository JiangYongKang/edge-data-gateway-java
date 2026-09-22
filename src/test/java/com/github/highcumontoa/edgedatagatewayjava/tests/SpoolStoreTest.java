package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolFullException;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖缓冲容量边界、确认幂等与重启恢复一致性。 */
class SpoolStoreTest {

    private static final Logger log = LoggerFactory.getLogger(SpoolStoreTest.class);

    private NormalizedRecord append(SpoolStore s, String device, int i) {
        return s.append(device, "p", DataType.DOUBLE, "C", (double) i, null, 1000L + i);
    }

    @Test
    void capacityBoundaryRejectsNewAndKeepsOldest(@TempDir Path dir) {
        SpoolStore spool = new SpoolStore();
        spool.init(dir, 3);
        append(spool, "d", 0);
        append(spool, "d", 1);
        append(spool, "d", 2);
        assertEquals(3, spool.pendingCount());

        SpoolFullException e = assertThrows(SpoolFullException.class, () -> append(spool, "d", 3));
        log.warn("[SPOOL-FULL] {}", e.getMessage());
        assertTrue(e.getMessage().contains("spool is full (3/3)"));
        assertTrue(e.getMessage().contains("oldest unacked seq=0"));

        // 缓冲仍为 3 条，最旧数据保留
        List<NormalizedRecord> pending = spool.pendingSorted(10);
        assertEquals(3, pending.size());
        assertEquals(0L, pending.get(0).seq());
    }

    @Test
    void acknowledgeIsIdempotentAndFreesCapacity(@TempDir Path dir) {
        SpoolStore spool = new SpoolStore();
        spool.init(dir, 2);
        append(spool, "d", 0);
        append(spool, "d", 1);
        // 确认后腾出容量
        spool.acknowledge(List.of(0L));
        append(spool, "d", 2);
        // 重复确认同一 seq 与确认未知 seq 均无副作用
        spool.acknowledge(List.of(0L, 0L, 999L));
        assertEquals(2, spool.pendingCount());
        assertEquals(List.of(1L, 2L),
                spool.pendingSorted(10).stream().map(NormalizedRecord::seq).toList());
    }

    @Test
    void recoversPendingAndSeqAfterRestart(@TempDir Path dir) {
        SpoolStore first = new SpoolStore();
        first.init(dir, 10);
        append(first, "d", 0);
        append(first, "d", 1);
        append(first, "d", 2);
        first.acknowledge(List.of(0L)); // 已确认记录压缩落盘
        long nextSeqBefore = first.nextSeq();
        log.info("[SPOOL-RESTART] before restart nextSeq={} pending={}",
                nextSeqBefore, first.pendingSorted(100).stream().map(NormalizedRecord::seq).toList());

        // 模拟进程重启：全新实例从同一目录恢复
        SpoolStore second = new SpoolStore();
        second.init(dir, 10);
        List<Long> seqs = second.pendingSorted(100).stream()
                .map(NormalizedRecord::seq).toList();
        log.info("[SPOOL-RESTART] after restart nextSeq={} pending={}",
                second.nextSeq(), seqs);

        assertEquals(List.of(1L, 2L), seqs);
        assertEquals(nextSeqBefore, second.nextSeq());
        // 恢复后序列号继续单调，不与历史冲突
        NormalizedRecord r3 = append(second, "d", 3);
        assertEquals(3L, r3.seq());
        // 未确认集合为 {1,2,3}，最旧位点仍是 1
        assertEquals(1L, second.oldestPendingSeq());
    }

    @Test
    void pendingAlwaysReturnedInSeqOrder(@TempDir Path dir) {
        SpoolStore spool = new SpoolStore();
        spool.init(dir, 10);
        append(spool, "dA", 0);
        append(spool, "dB", 1);
        append(spool, "dA", 2);
        append(spool, "dB", 3);
        List<Long> seqs = spool.pendingSorted(100).stream()
                .map(NormalizedRecord::seq).toList();
        assertEquals(List.of(0L, 1L, 2L, 3L), seqs);
    }
}

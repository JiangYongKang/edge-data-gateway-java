package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.buffer.AcceptResult;
import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferStateStore;
import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缓冲位点、有界溢出边界与重启恢复一致性测试。 */
class SpoolBufferTest {

    private static final Logger log = LoggerFactory.getLogger(SpoolBufferTest.class);

    @TempDir
    Path dir;

    @Test
    void assignsMonotonicSeqAndConfirmOnlyAdvances() throws Exception {
        SpoolBuffer buf = newBuffer(dir, 10, OverflowPolicy.DROP_OLDEST);
        buf.recover();

        AcceptResult r0 = buf.append(rec("d", "p", 1.0, 100));
        AcceptResult r1 = buf.append(rec("d", "p", 2.0, 110));
        AcceptResult r2 = buf.append(rec("d", "p", 3.0, 120));
        log.info("[补传位点] appends: seq={},{},{} confirmedSeq={}",
                r0.seq(), r1.seq(), r2.seq(), buf.confirmedSeq());
        assertEquals(List.of(0L, 1L, 2L), List.of(r0.seq(), r1.seq(), r2.seq()));
        assertEquals(3, buf.size());

        assertTrue(buf.confirm(1L));
        assertEquals(1L, buf.confirmedSeq());
        // 重复确认无效
        assertFalse(buf.confirm(1L));
        // 回退确认无效
        assertFalse(buf.confirm(0L));
        assertEquals(1L, buf.confirmedSeq());
        // 确认未分配位点无效
        assertFalse(buf.confirm(99L));
        // confirm(1) 表示确认到 seq=1（含），只剩 seq=2
        assertEquals(2L, buf.pending().get(0).seq());
        assertEquals(1, buf.pending().size());
        log.info("[补传位点] after duplicate/backward confirm: confirmedSeq={}",
                buf.confirmedSeq());
    }

    @Test
    void dropOldestAtCapacityIsDeterministic() throws Exception {
        SpoolBuffer buf = newBuffer(dir, 3, OverflowPolicy.DROP_OLDEST);
        buf.recover();
        buf.append(rec("d", "p", 0.0, 0));
        buf.append(rec("d", "p", 1.0, 1));
        buf.append(rec("d", "p", 2.0, 2));

        AcceptResult overflow = buf.append(rec("d", "p", 3.0, 3));
        assertTrue(overflow.accepted());
        assertEquals(1, overflow.dropped().size());
        assertEquals(0L, overflow.dropped().get(0).seq(), "oldest seq=0 must be evicted");
        assertEquals(3, buf.size());
        assertEquals(List.of(1L, 2L, 3L), seqs(buf.pending()));
        // 被丢弃记录的位点被视为已确认，位点不回退、不重投
        assertEquals(0L, buf.confirmedSeq());
        log.info("[补传位点] DROP_OLDEST overflow: droppedSeq={}, pendingSeqs={}",
                overflow.dropped().get(0).seq(), seqs(buf.pending()));

        AcceptResult overflow2 = buf.append(rec("d", "p", 4.0, 4));
        assertEquals(List.of(1L), seqs(overflow2.dropped()));
        assertEquals(List.of(2L, 3L, 4L), seqs(buf.pending()));
    }

    @Test
    void rejectNewestAtCapacityKeepsOldestAndConsumesSeq() throws Exception {
        SpoolBuffer buf = newBuffer(dir, 3, OverflowPolicy.REJECT_NEWEST);
        buf.recover();
        buf.append(rec("d", "p", 0.0, 0));
        buf.append(rec("d", "p", 1.0, 1));
        buf.append(rec("d", "p", 2.0, 2));

        AcceptResult rejected = buf.append(rec("d", "p", 3.0, 3));
        assertFalse(rejected.accepted());
        assertEquals(List.of(0L, 1L, 2L), seqs(buf.pending()));
        // 新记录未入缓冲，但 seq 已消耗（缺口可解释、全局单调）
        assertEquals(4L, buf.nextSeq());
        assertEquals(3L, rejected.seq());

        assertTrue(buf.confirm(2L));
        AcceptResult acceptedAfter = buf.append(rec("d", "p", 4.0, 4));
        assertTrue(acceptedAfter.accepted());
        assertEquals(4L, acceptedAfter.seq());
        log.info("[补传位点] REJECT_NEWEST: rejected seq=3 (gap), then accepted seq={}, pending={}",
                acceptedAfter.seq(), seqs(buf.pending()));
    }

    @Test
    void restartReplaysOnlyUnconfirmedAndPreservesPosition() throws Exception {
        SpoolBuffer buf = newBuffer(dir, 10, OverflowPolicy.DROP_OLDEST);
        buf.recover();
        for (int i = 0; i < 5; i++) {
            buf.append(rec("d" + (i % 2), "p", i, 100 + i));
        }
        assertTrue(buf.confirm(2L));
        log.info("[补传位点] before restart: confirmedSeq={} pending={}",
                buf.confirmedSeq(), seqs(buf.pending()));

        // 重启：同一目录、全新缓冲实例
        SpoolBuffer reopened = newBuffer(dir, 10, OverflowPolicy.DROP_OLDEST);
        reopened.recover();
        assertEquals(5L, reopened.nextSeq(), "nextSeq restored");
        assertEquals(2L, reopened.confirmedSeq(), "confirmedSeq restored, no data loss/dup");
        assertEquals(List.of(3L, 4L), seqs(reopened.pending()),
                "only unconfirmed records replayed, order kept");
        assertEquals(Long.valueOf(104L), reopened.lastTimestamp("d0"));
        assertEquals(Long.valueOf(103L), reopened.lastTimestamp("d1"));

        // 重启后新分配的 seq 接着走，不复用旧号
        AcceptResult r = reopened.append(rec("d0", "p", 9.0, 200));
        assertEquals(5L, r.seq());
        log.info("[补传位点] after restart: confirmedSeq={} pending={} newSeq={}",
                reopened.confirmedSeq(), seqs(reopened.pending()), r.seq());
    }

    @Test
    void restartAfterDropOldestHasNoStaleReplay() throws Exception {
        SpoolBuffer buf = newBuffer(dir, 2, OverflowPolicy.DROP_OLDEST);
        buf.recover();
        buf.append(rec("d", "p", 0.0, 0));
        buf.append(rec("d", "p", 1.0, 1));
        AcceptResult dropped = buf.append(rec("d", "p", 2.0, 2));
        assertEquals(List.of(0L), seqs(dropped.dropped()));

        SpoolBuffer reopened = newBuffer(dir, 2, OverflowPolicy.DROP_OLDEST);
        reopened.recover();
        assertEquals(List.of(1L, 2L), seqs(reopened.pending()),
                "evicted record must not reappear after restart");
        assertEquals(3L, reopened.nextSeq());
        log.info("[补传位点] restart after DROP_OLDEST: pending={} nextSeq={}",
                seqs(reopened.pending()), reopened.nextSeq());
    }

    private static SpoolBuffer newBuffer(Path dir, int capacity, OverflowPolicy policy) {
        return new SpoolBuffer(
                new BufferStateStore.JsonFile(dir.resolve("state.json")),
                dir.resolve("spool.wal"), capacity, policy);
    }

    private static NormalizedRecord rec(String device, String point, double value, long ts) {
        return new NormalizedRecord(-1L, device, point, DataType.DOUBLE, value, "C", ts);
    }

    private static List<Long> seqs(List<NormalizedRecord> records) {
        return records.stream().map(NormalizedRecord::seq).toList();
    }
}

package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.upstream.ChannelException;
import com.github.highcumontoa.edgedatagatewayjava.upstream.MockUpstreamChannel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 断网失败语义、seq 去重与自然键去重。 */
class MockUpstreamChannelTest {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamChannelTest.class);

    private NormalizedRecord rec(long seq, String device, String point, long ts) {
        return new NormalizedRecord(seq, device, point, DataType.DOUBLE, "C",
                1.0 + seq, null, ts);
    }

    @Test
    void offlineDeliversNothingAndThrows() {
        MockUpstreamChannel ch = new MockUpstreamChannel();
        ch.disconnect();
        List<NormalizedRecord> batch = List.of(rec(0, "d", "p", 1000L));
        assertThrows(ChannelException.class, () -> ch.deliver(batch));
        assertEquals(0, ch.deliveredCount());
        ch.reconnect();
        assertEquals(List.of(0L), ch.deliver(batch));
        assertEquals(1, ch.deliveredCount());
    }

    @Test
    void duplicateSeqDeliveryCreatesNoDuplicate() {
        MockUpstreamChannel ch = new MockUpstreamChannel();
        var r = rec(7, "d", "p", 1000L);
        ch.deliver(List.of(r));
        ch.deliver(List.of(r));          // 重复投递（重试）
        ch.deliver(List.of(r));          // 再次重复
        assertEquals(1, ch.deliveredCount());
        assertEquals(7L, ch.deliveredRecords().get(0).seq());
        log.info("[DEDUP-SEQ] 重复投递 3 次后下游记录数={}", ch.deliveredCount());
    }

    @Test
    void naturalKeyDedupAcrossDifferentSeq() {
        MockUpstreamChannel ch = new MockUpstreamChannel();
        // 崩溃重采：同一设备/点位/时间戳，被分配了新 seq
        var r1 = rec(3, "d", "p", 1000L);
        var r2 = rec(11, "d", "p", 1000L);
        List<Long> ack1 = ch.deliver(List.of(r1));
        List<Long> ack2 = ch.deliver(List.of(r2));
        log.info("[DEDUP-NATURAL] first ack={} second ack={} downstreamCount={} suppressed={}",
                ack1, ack2, ch.deliveredCount(), ch.duplicateSuppressed());
        assertEquals(1, ch.deliveredCount());
        assertEquals(List.of(3L, 11L), java.util.stream.Stream.concat(ack1.stream(), ack2.stream()).toList());
        assertTrue(ch.containsSeq(3L));
    }

    @Test
    void transientFailureIsAllOrNothing() {
        MockUpstreamChannel ch = new MockUpstreamChannel();
        ch.injectTransientFailures(1);
        var batch = List.of(rec(0, "d", "p", 1000L), rec(1, "d", "p", 2000L));
        assertThrows(ChannelException.class, () -> ch.deliver(batch));
        assertEquals(0, ch.deliveredCount());
        // 下次重试整批成功
        assertEquals(List.of(0L, 1L), ch.deliver(batch));
        assertEquals(2, ch.deliveredCount());
    }
}

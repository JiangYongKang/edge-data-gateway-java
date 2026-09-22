package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferStateStore;
import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardListener;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardWorker;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkChannel;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 断网重连、顺序补传、重复投递/确认幂等测试。 */
class ForwardWorkerTest {

    private static final Logger log = LoggerFactory.getLogger(ForwardWorkerTest.class);

    @TempDir
    Path dir;

    @Test
    void buffersWhileDisconnectedAndReplaysInOrderAfterReconnect() throws Exception {
        SpoolBuffer buf = newBuffer(dir.resolve("case1"));
        buf.recover();
        MockUplinkChannel channel = new MockUplinkChannel(false); // 启动即断网
        CountingListener listener = new CountingListener();
        ForwardWorker worker = new ForwardWorker(buf, channel, listener, 20L, 100);
        worker.start();

        for (int i = 0; i < 10; i++) {
            buf.append(rec(i, 100 + i));
            worker.kick();
        }
        Thread.sleep(200);
        assertTrue(channel.delivered().isEmpty(), "nothing delivered while disconnected");
        assertEquals(10, buf.size(), "all records stay spooled");
        assertEquals(-1L, buf.confirmedSeq(), "no confirm without successful send");
        log.info("[补传位点] disconnected: confirmedSeq={}, pending={}",
                buf.confirmedSeq(), buf.size());

        channel.reconnect();
        worker.kick();
        DemoRunner.await(() -> buf.size() == 0, 3000);
        assertEquals(9L, buf.confirmedSeq());

        List<Long> seqs = channel.delivered().stream().map(NormalizedRecord::seq).toList();
        assertEquals(10, seqs.size());
        List<Long> expected = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            expected.add(i);
        }
        assertEquals(expected, seqs, "replay must keep seq order with no gaps");
        log.info("[补传位点] replayed in order: {}", seqs);
        worker.stop();
    }

    @Test
    void repeatedKicksAndDuplicateSendsProduceNoDuplicates() throws Exception {
        SpoolBuffer buf = newBuffer(dir.resolve("case2"));
        buf.recover();
        MockUplinkChannel channel = new MockUplinkChannel(true);
        CountingListener listener = new CountingListener();
        ForwardWorker worker = new ForwardWorker(buf, channel, listener, 20L, 100);
        worker.start();

        for (int i = 0; i < 20; i++) {
            buf.append(rec(i, 100 + i));
        }
        // 大量并发 kick 触发重叠排空；重叠由原子位防护，且 send/confirm 幂等
        List<Thread> kickers = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    worker.kick();
                }
            });
            kickers.add(th);
            th.start();
        }
        for (Thread th : kickers) {
            th.join();
        }
        DemoRunner.await(() -> buf.size() == 0, 3000);

        List<Long> seqs = channel.delivered().stream().map(NormalizedRecord::seq).sorted().toList();
        assertEquals(20, seqs.size(), "no duplicate downstream records");
        assertEquals(seqs.stream().distinct().count(), seqs.size(), "seqs unique");
        // 每条记录只在一次位点推进事件中被计数（重复确认不重复计数）
        assertEquals(20, listener.confirmedRecords.get(),
                "each record confirmed exactly once");
        assertTrue(listener.confirmEvents.get() <= 20);
        assertEquals(19L, buf.confirmedSeq());
        log.info("[补传位点] concurrent kicks settled: confirmedSeq={}, events={}, records={}",
                buf.confirmedSeq(), listener.confirmEvents.get(),
                listener.confirmedRecords.get());
        worker.stop();
    }

    @Test
    void channelIdempotentOnIdenticalBatch() throws Exception {
        MockUplinkChannel channel = new MockUplinkChannel(true);
        List<NormalizedRecord> batch = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            batch.add(withSeq(rec(i, 100 + i), i));
        }
        long ack1 = channel.send(batch);
        long ack2 = channel.send(batch); // 模拟重传：同一批再投一次
        assertEquals(4L, ack1);
        assertEquals(4L, ack2);
        assertEquals(5, channel.delivered().size(), "duplicate send must not duplicate records");
        log.info("[补传位点] duplicate batch send: downstream size={}",
                channel.delivered().size());
    }

    @Test
    void resumesFromConfirmedPositionAfterWorkerRestart() throws Exception {
        Path d = dir.resolve("case4");
        SpoolBuffer buf = newBuffer(d);
        buf.recover();
        // 前两次发送必失败（模拟首程断网），之后恢复
        UplinkChannel flaky = new UplinkChannel() {
            private final MockUplinkChannel delegate = new MockUplinkChannel(true);
            private final AtomicInteger attempts = new AtomicInteger(2);

            @Override
            public long send(List<NormalizedRecord> batch) throws UplinkException {
                if (attempts.getAndDecrement() > 0) {
                    throw new UplinkException("simulated initial outage");
                }
                return delegate.send(batch);
            }

            @Override
            public boolean connected() {
                return true;
            }
        };
        CountingListener l1 = new CountingListener();
        ForwardWorker w1 = new ForwardWorker(buf, flaky, l1, 20L, 100);
        w1.start();
        buf.append(rec(0, 100));
        DemoRunner.await(() -> buf.confirmedSeq() == 0L, 3000);
        w1.stop();
        // w1 已停：再产生一条记录，它只会落 WAL，不会被发送
        buf.append(rec(1, 101));
        log.info("[补传位点] worker1 stopped after ack 0, pending={}", seqs(buf.pending()));

        // 网关重启：位点与 WAL 恢复后继续
        SpoolBuffer reopened = newBuffer(d);
        reopened.recover();
        assertEquals(0L, reopened.confirmedSeq());
        assertEquals(List.of(1L), seqs(reopened.pending()));
        MockUplinkChannel channel2 = new MockUplinkChannel(false);
        CountingListener l2 = new CountingListener();
        ForwardWorker w2 = new ForwardWorker(reopened, channel2, l2, 20L, 100);
        w2.start();
        Thread.sleep(150);
        // 通道仍断开：seq1 必须留在缓冲，位点不动
        assertEquals(List.of(1L), seqs(reopened.pending()));
        assertEquals(0L, reopened.confirmedSeq());
        channel2.reconnect();
        w2.kick();
        DemoRunner.await(() -> reopened.size() == 0, 3000);
        assertEquals(1L, reopened.confirmedSeq(), "continues after confirmed position, no replay of seq0");
        assertEquals(List.of(1L),
                channel2.delivered().stream().map(NormalizedRecord::seq).toList());
        log.info("[补传位点] worker2 resumed: confirmedSeq={}, delivered={}",
                reopened.confirmedSeq(),
                channel2.delivered().stream().map(NormalizedRecord::seq).toList());
        w2.stop();
    }

    private static SpoolBuffer newBuffer(Path dir) {
        return new SpoolBuffer(
                new BufferStateStore.JsonFile(dir.resolve("state.json")),
                dir.resolve("spool.wal"), 1000, OverflowPolicy.DROP_OLDEST);
    }

    private static NormalizedRecord rec(int value, long ts) {
        return new NormalizedRecord(-1L, "d1", "temp", DataType.DOUBLE, (double) value, "C", ts);
    }

    private static NormalizedRecord withSeq(NormalizedRecord r, long seq) {
        return new NormalizedRecord(seq, r.deviceId(), r.pointId(), r.type(), r.value(),
                r.unit(), r.timestamp());
    }

    private static List<Long> seqs(List<NormalizedRecord> rs) {
        return rs.stream().map(NormalizedRecord::seq).toList();
    }

    /** 统计位点推进事件：onConfirmed 只应在位点真正推进时触发一次。 */
    private static final class CountingListener implements ForwardListener {
        final AtomicInteger confirmEvents = new AtomicInteger(0);
        final AtomicInteger confirmedRecords = new AtomicInteger(0);
        final AtomicInteger downEvents = new AtomicInteger(0);
        final AtomicInteger upEvents = new AtomicInteger(0);

        @Override
        public void onConfirmed(long confirmedSeq, int recordsThisBatch) {
            confirmEvents.incrementAndGet();
            confirmedRecords.addAndGet(recordsThisBatch);
            log.info("[补传位点] onConfirmed seq={} n={}", confirmedSeq, recordsThisBatch);
        }

        @Override
        public void onUplinkDown(Throwable error) {
            downEvents.incrementAndGet();
        }

        @Override
        public void onUplinkUp() {
            upEvents.incrementAndGet();
        }
    }
}

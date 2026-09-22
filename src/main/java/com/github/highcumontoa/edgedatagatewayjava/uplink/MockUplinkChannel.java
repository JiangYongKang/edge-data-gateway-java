package com.github.highcumontoa.edgedatagatewayjava.uplink;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地模拟上行通道。
 *
 * <ul>
 *   <li>{@link #disconnect()} / {@link #reconnect()} 模拟断网与恢复；断网时 send 抛
 *       {@link UplinkException}，调用方不得确认位点。</li>
 *   <li>下游按全局 {@code seq} 去重：同一 seq 无论重复投递多少次，只产生一条下游记录。</li>
 *   <li>所有方法线程安全，{@link #delivered()} 返回按 seq 升序的快照。</li>
 * </ul>
 */
public final class MockUplinkChannel implements UplinkChannel {

    private final Set<Long> deliveredSeqs = new TreeSet<>();
    private final List<NormalizedRecord> delivered = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    public MockUplinkChannel(boolean initiallyConnected) {
        this.connected = initiallyConnected;
    }

    @Override
    public long send(List<NormalizedRecord> batch) throws UplinkException {
        if (!connected) {
            throw new UplinkException("uplink disconnected");
        }
        if (batch.isEmpty()) {
            throw new UplinkException("empty batch");
        }
        long maxSeq = Long.MIN_VALUE;
        synchronized (this) {
            if (!connected) {
                throw new UplinkException("uplink disconnected");
            }
            for (NormalizedRecord rec : batch) {
                if (deliveredSeqs.add(rec.seq())) {
                    delivered.add(rec);
                }
                // 重复 seq：幂等跳过，不产生重复下游记录
                maxSeq = Math.max(maxSeq, rec.seq());
            }
        }
        return maxSeq;
    }

    @Override
    public synchronized boolean connected() {
        return connected;
    }

    public synchronized void disconnect() {
        this.connected = false;
    }

    public synchronized void reconnect() {
        this.connected = true;
    }

    /** 下游实际收到的记录（按 seq 升序、无重复）。 */
    public synchronized List<NormalizedRecord> delivered() {
        List<NormalizedRecord> copy = new ArrayList<>(delivered);
        copy.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        return copy;
    }
}

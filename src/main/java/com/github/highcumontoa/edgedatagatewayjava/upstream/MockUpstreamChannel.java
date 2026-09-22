package com.github.highcumontoa.edgedatagatewayjava.upstream;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 本地模拟上行通道。
 *
 * <p>关键语义（与真实下游服务对齐）：
 * <ul>
 *   <li>{@link #disconnect()} 后 {@link #deliver} 一律抛 {@link ChannelException}；
 *       {@link #reconnect()} 后恢复。</li>
 *   <li>下游以 seq 为主键保存：同一 seq 重复投递只确认、不产生第二条记录，
 *       因此“重复投递/重复确认”在下游保持幂等。</li>
 *   <li>每批交付是“全有或全无”：离线或注入故障时该批不写入任何记录、不确认任何 seq，
 *       调用方必须整批保留在缓冲中。</li>
 *   <li>支持注入连续 N 次瞬时失败（通道看似在线但交付失败），用于验证重试补传。</li>
 * </ul>
 */
public class MockUpstreamChannel implements UpstreamChannel {

    private volatile boolean online = true;

    /** 剩余瞬时失败次数（在线但交付抛异常）。 */
    private volatile int transientFailures;

    /** seq -> 下游已保存记录，TreeMap 保持升序。 */
    private final TreeMap<Long, NormalizedRecord> delivered = new TreeMap<>();

    /** 自然键去重索引：deviceId|pointId|timestamp -> seq。 */
    private final java.util.Map<String, Long> naturalIndex = new java.util.HashMap<>();

    /** 累计投递尝试次数（含失败），用于测试观测。 */
    private volatile long deliveryAttempts;

    /** 因重复自然键被幂等忽略（未新写入）的次数。 */
    private volatile long duplicateSuppressed;

    @Override
    public boolean isOnline() {
        return online;
    }

    @Override
    public synchronized List<Long> deliver(List<NormalizedRecord> batch) {
        deliveryAttempts++;
        if (!online) {
            throw new ChannelException("upstream channel is OFFLINE; batch of "
                    + batch.size() + " rejected, nothing acknowledged");
        }
        if (transientFailures > 0) {
            transientFailures--;
            throw new ChannelException("injected transient failure (" + (transientFailures + 1)
                    + " left); batch of " + batch.size() + " rejected, nothing acknowledged");
        }
        List<Long> acked = new ArrayList<>(batch.size());
        for (NormalizedRecord r : batch) {
            String naturalKey = naturalKey(r);
            Long existingSeq = naturalIndex.get(naturalKey);
            if (existingSeq != null) {
                // 自然键重复（崩溃重采产生新 seq）：不新增下游记录，但确认当前 seq，
                // 使其可从缓冲清理。这是“重复投递不产生重复记录”的第二道防线。
                duplicateSuppressed++;
                acked.add(r.seq());
                continue;
            }
            // 以 seq 为主键保存：同一 seq 重复投递只确认、不产生第二条记录。
            NormalizedRecord exists = delivered.putIfAbsent(r.seq(), r);
            if (exists != null && !exists.equals(r)) {
                // 同一 seq 对应不同内容属于程序错误，快速暴露而不是静默接受。
                throw new IllegalStateException(
                        "seq " + r.seq() + " collision with different record content");
            }
            naturalIndex.put(naturalKey, r.seq());
            acked.add(r.seq());
        }
        return List.copyOf(acked);
    }

    /** 模拟断网：此后 deliver 一律失败，直到 {@link #reconnect()}。 */
    public void disconnect() {
        this.online = false;
    }

    /** 模拟恢复：此后 deliver 恢复成功。 */
    public void reconnect() {
        this.online = true;
    }

    /** 注入 n 次瞬时交付失败（仍报告在线），每次 deliver 消耗一次。 */
    public void injectTransientFailures(int n) {
        this.transientFailures = n;
    }

    /** 下游实际保存的记录（按 seq 升序），用于断言“无重复且有序”。 */
    public synchronized List<NormalizedRecord> deliveredRecords() {
        return new ArrayList<>(delivered.values());
    }

    /** 下游实际保存的记录数（按 seq 去重后）。 */
    public synchronized int deliveredCount() {
        return delivered.size();
    }

    /** 下游是否已存在某 seq。 */
    public synchronized boolean containsSeq(long seq) {
        return delivered.containsKey(seq);
    }

    public long deliveryAttempts() {
        return deliveryAttempts;
    }

    /** 因自然键重复被幂等忽略的投递条数。 */
    public long duplicateSuppressed() {
        return duplicateSuppressed;
    }

    private static String naturalKey(NormalizedRecord r) {
        return r.deviceId() + "|" + r.pointId() + "|" + r.timestamp();
    }
}

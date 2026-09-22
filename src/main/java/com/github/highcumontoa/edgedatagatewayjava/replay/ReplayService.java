package com.github.highcumontoa.edgedatagatewayjava.replay;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import com.github.highcumontoa.edgedatagatewayjava.upstream.ChannelException;
import com.github.highcumontoa.edgedatagatewayjava.upstream.UpstreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补传服务。
 *
 * <p>断网缓冲与补传语义：
 * <ul>
 *   <li>采集与补传解耦：断网期间记录持续 append 到持久 spool，不丢数据。</li>
 *   <li>补传严格按全局 seq 升序（即入缓冲时间顺序）批量投递；
 *       通道离线或任一批投递失败时，<strong>整批不确认、缓冲不变</strong>，
 *       并从最旧未确认 seq 处停止本轮，等待下个周期重试——
 *       因此绝不会越过缺口先投后面的数据。</li>
 *   <li>仅在通道成功返回后才 {@code acknowledge}；确认幂等，重复确认无副作用。</li>
 *   <li>下游对 seq 与自然键双重去重，重复投递不会产生重复下游记录。</li>
 * </ul>
 */
@Component
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** 单批最大记录数，保证顺序与可解释的批次边界。 */
    private final int batchSize = 50;

    private SpoolStore spool;
    private UpstreamChannel channel;

    private ScheduledExecutorService executor;
    private volatile boolean started;

    private final AtomicLong flushRounds = new AtomicLong();
    private final AtomicLong totalAcked = new AtomicLong();
    private final AtomicLong failedRounds = new AtomicLong();
    private volatile String lastError;

    /** 装配依赖（不启动定时任务）。 */
    public void bind(SpoolStore spool, UpstreamChannel channel) {
        this.spool = spool;
        this.channel = channel;
    }

    /** 启动周期补传（独立单线程）。 */
    public synchronized void start(long intervalMs) {
        if (started) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replay-worker");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::flushSafely,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        started = true;
    }

    /** 停止周期补传。 */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        executor.shutdownNow();
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (Throwable t) {
            // 补传线程永不因未预期异常死亡。
            failedRounds.incrementAndGet();
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.error("[REPLAY-ERROR] unexpected failure, buffer unchanged", t);
        }
    }

    /**
     * 执行一轮补传；返回本轮成功确认的记录数。
     * 通道离线/失败时缓冲不变并尽早返回。
     */
    public int flushOnce() {
        flushRounds.incrementAndGet();
        int ackedThisRound = 0;

        while (true) {
            List<NormalizedRecord> batch;
            synchronized (spool) {
                batch = spool.pendingSorted(batchSize);
            }
            if (batch.isEmpty()) {
                break;
            }

            long firstSeq = batch.get(0).seq();
            long lastSeq = batch.get(batch.size() - 1).seq();
            if (!channel.isOnline()) {
                log.info("[REPLAY-HOLD] channel OFFLINE, keep seq[{}, {}] in spool (pending={})",
                        firstSeq, lastSeq, spool.pendingCount());
                break;
            }

            List<Long> acked;
            try {
                acked = channel.deliver(batch);
            } catch (ChannelException e) {
                failedRounds.incrementAndGet();
                lastError = e.getMessage();
                // 整批保留：不确认任何记录，从缺口处停止，等待重试。
                log.warn("[REPLAY-RETRY] deliver failed at seq[{}, {}], buffer unchanged: {}",
                        firstSeq, lastSeq, e.getMessage());
                break;
            }

            // 成功：按返回的 seq 幂等确认（通常等于整批）。
            spool.acknowledge(acked);
            ackedThisRound += acked.size();
            totalAcked.addAndGet(acked.size());
            lastError = null;
            log.info("[REPLAY-ACK] delivered+acked seq[{}, {}], count={}, cursorAfter={}, pending={}",
                    firstSeq, lastSeq, acked.size(), lastSeq, spool.pendingCount());

            if (batch.size() < batchSize) {
                break;
            }
        }
        return ackedThisRound;
    }

    public boolean isStarted() {
        return started;
    }

    public long flushRounds() {
        return flushRounds.get();
    }

    public long totalAcked() {
        return totalAcked.get();
    }

    public long failedRounds() {
        return failedRounds.get();
    }

    public String lastError() {
        return lastError;
    }
}

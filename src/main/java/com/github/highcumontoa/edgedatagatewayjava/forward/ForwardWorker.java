package com.github.highcumontoa.edgedatagatewayjava.forward;

import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkChannel;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkException;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 补传工作线程。
 *
 * <ul>
 *   <li>单线程调度，始终取 {@code pending()} 头部（seq 最小）成批发送，保证时间/seq 顺序。</li>
 *   <li>发送成功才调用 {@code buffer.confirm(maxSeq)}；{@code send} 幂等 +
 *       {@code confirm} 只进不退，因此重复调度、重复确认都不会在下游产生重复或缺口。</li>
 *   <li>断网时等待下一个调度周期重试，不会阻塞采集线程；恢复后自动排空积压。</li>
 * </ul>
 */
public final class ForwardWorker {

    private final SpoolBuffer buffer;
    private final UplinkChannel channel;
    private final ForwardListener listener;
    private final long drainIntervalMillis;
    private final int batchSize;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile boolean down = false;

    public ForwardWorker(SpoolBuffer buffer,
                         UplinkChannel channel,
                         ForwardListener listener,
                         long drainIntervalMillis,
                         int batchSize) {
        this.buffer = buffer;
        this.channel = channel;
        this.listener = listener;
        this.drainIntervalMillis = drainIntervalMillis;
        this.batchSize = batchSize;
    }

    public void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "forward-worker");
            t.setDaemon(true);
            return t;
        });
        task = executor.scheduleWithFixedDelay(this::drain,
                drainIntervalMillis, drainIntervalMillis, TimeUnit.MILLISECONDS);
        this.down = !channel.connected();
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    /** 有新记录入缓冲时唤醒一次尽快补传（调度重叠由 drainOnce 原子位防护）。 */
    public void kick() {
        if (executor == null) {
            return;
        }
        executor.submit(this::drain);
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            // 一次唤醒尽量排空，避免积压时分多批等待
            while (drainOnce()) {
                // continue
            }
        } finally {
            draining.set(false);
        }
    }

    /**
     * @return 是否成功处理了一批（调用方应继续尝试排空）；断网/空队列返回 false
     */
    private boolean drainOnce() {
        List<NormalizedRecord> batch = buffer.pending();
        if (batch.isEmpty()) {
            if (down) {
                down = false;
                listener.onUplinkUp();
            }
            return false;
        }
        if (batch.size() > batchSize) {
            batch = batch.subList(0, batchSize);
        }
        long ackSeq;
        try {
            ackSeq = channel.send(batch);
        } catch (UplinkException e) {
            if (!down) {
                down = true;
                listener.onUplinkDown(e);
            }
            return false;
        }
        // 确认位点 + 按设备归属计数由网关在同一把写锁内原子完成，避免并发下计数自相矛盾
        long maxBatchSeq = batch.get(batch.size() - 1).seq();
        long toConfirm = Math.min(ackSeq, maxBatchSeq);
        boolean advanced = listener.onConfirmed(batch, toConfirm);
        if (down) {
            down = false;
            listener.onUplinkUp();
        }
        if (advanced) {
            // 成功且位点推进：若还有积压继续排空
            return true;
        }
        // 通道虽返回成功但位点未推进（异常 ack）：停止本轮排空，等下周期，避免自旋
        return false;
    }
}

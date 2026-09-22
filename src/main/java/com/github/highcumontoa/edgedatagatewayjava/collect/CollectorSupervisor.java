package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.normalize.ReadingNormalizer;
import com.github.highcumontoa.edgedatagatewayjava.spool.DeviceCheckpointStore;
import com.github.highcumontoa.edgedatagatewayjava.spool.RejectionLog;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolFullException;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 采集监督器。
 *
 * <p>并发与故障边界：
 * <ul>
 *   <li>每个设备拥有独立的单线程调度执行器，设备间线程隔离；
 *       单设备任务抛出的任何异常都被捕获并记录到该设备统计，
 *       不会向上传播、不会影响其它设备。</li>
 *   <li>采集按“位点”消费：offset 与最近已接受时间戳在每条读数处理后原子持久化；
 *       缓冲满（背压）时该条读数的位点<strong>不推进</strong>，
 *       下轮从同一读数重试，因此不会跳过或丢失数据。</li>
 *   <li>畸形读数被拒绝并落 rejection 日志后，位点照常推进，绝不重试脏值。</li>
 *   <li>在线状态仅反映“最近一次设备轮询是否成功”，统计随实际处理实时更新。</li>
 * </ul>
 */
@Component
public class CollectorSupervisor {

    private static final Logger log = LoggerFactory.getLogger(CollectorSupervisor.class);

    private final ReadingNormalizer normalizer;
    private final DeviceCollectorTask task;
    private final SpoolStore spool;
    private final DeviceCheckpointStore checkpoints;
    private final RejectionLog rejectionLog;

    private final Map<String, DeviceContext> contexts = new ConcurrentHashMap<>();

    private volatile int pollBatchSize = 10;
    private volatile long intervalMs = 200;
    private volatile boolean started;

    /** 每设备独立调度器，保证故障隔离。 */
    private ScheduledExecutorService scheduler;

    private record DeviceContext(
            Device device,
            DeviceStats stats,
            ScheduledExecutorService executor,
            Future<?> future) {
    }

    public CollectorSupervisor(ReadingNormalizer normalizer,
                               DeviceCollectorTask task,
                               SpoolStore spool,
                               DeviceCheckpointStore checkpoints,
                               RejectionLog rejectionLog) {
        this.normalizer = normalizer;
        this.task = task;
        this.spool = spool;
        this.checkpoints = checkpoints;
        this.rejectionLog = rejectionLog;
    }

    /** 装配设备列表并从持久化位点恢复（不启动周期任务）。 */
    public synchronized void bind(List<Device> devices, int pollBatchSize, long intervalMs) {
        this.pollBatchSize = pollBatchSize;
        this.intervalMs = intervalMs;
        for (Device device : devices) {
            DeviceStats stats = new DeviceStats(device.deviceId());
            stats.setOffset(checkpoints.loadOffset(device.deviceId()));
            contexts.put(device.deviceId(), new DeviceContext(
                    device, stats,
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "collector-" + device.deviceId());
                        t.setDaemon(true);
                        return t;
                    }),
                    null));
        }
        this.scheduler = null; // 设备各自持有执行器
    }

    /** 启动全部设备的周期采集。 */
    public synchronized void startAll() {
        if (started) {
            return;
        }
        started = true;
        for (DeviceContext ctx : contexts.values()) {
            DeviceStats stats = ctx.stats();
            Future<?> future = ctx.executor().scheduleWithFixedDelay(
                    () -> runCycleSafely(ctx), 0, intervalMs, TimeUnit.MILLISECONDS);
            contexts.put(ctx.device().deviceId(),
                    new DeviceContext(ctx.device(), stats, ctx.executor(), future));
        }
    }

    /** 停止全部采集任务并释放每设备执行器。 */
    public synchronized void stopAll() {
        if (!started) {
            return;
        }
        started = false;
        for (DeviceContext ctx : contexts.values()) {
            ctx.future().cancel(false);
            ctx.executor().shutdownNow();
        }
        contexts.replaceAll((id, ctx) -> new DeviceContext(
                ctx.device(), ctx.stats(), ctx.executor(), null));
    }

    /** 设备脚本是否已耗尽（无更多读数且当前无背压）。 */
    public boolean isDeviceDrained(String deviceId) {
        DeviceContext ctx = contexts.get(deviceId);
        if (ctx == null) {
            return true;
        }
        Device d = ctx.device();
        long offset = ctx.stats().offset();
        return d instanceof com.github.highcumontoa.edgedatagatewayjava.device.MockDevice md
                ? offset >= md.scriptSize()
                : d.poll(offset, 1).isEmpty();
    }

    /**
     * 手动同步触发一次某设备的采集周期（供测试与“立即采集”使用）。
     * 返回该周期处理的读数条数（含接受与拒绝，不含因背压未推进的那条）。
     */
    public long collectOnce(String deviceId) {
        DeviceContext ctx = contexts.get(deviceId);
        if (ctx == null) {
            throw new IllegalArgumentException("unknown device: " + deviceId);
        }
        return runCycle(ctx);
    }

    /** 包装周期执行：任何异常都不允许逃逸出该设备的线程。 */
    private void runCycleSafely(DeviceContext ctx) {
        try {
            runCycle(ctx);
        } catch (Throwable t) {
            // 设备轮询本身的致命错误：隔离到此设备，其他设备不受影响。
            ctx.stats().setOnline(false);
            ctx.stats().recordError(t.getClass().getSimpleName() + ": " + t.getMessage());
            log.error("[COLLECTOR-ERROR] device={} cycle failed, isolated to this device",
                    ctx.device().deviceId(), t);
        }
    }

    /**
     * 执行一个采集周期。
     *
     * @return 本周期位点实际推进的读数条数
     */
    private long runCycle(DeviceContext ctx) {
        Device device = ctx.device();
        DeviceStats stats = ctx.stats();
        long offset = stats.offset();
        long lastTs = checkpoints.loadLastTimestamp(device.deviceId());

        List<RawReading> batch;
        try {
            batch = device.poll(offset, pollBatchSize);
            stats.setOnline(true);
        } catch (RuntimeException e) {
            stats.setOnline(false);
            stats.recordError(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("[COLLECTOR-POLL-FAIL] device={} {}", device.deviceId(), e.getMessage());
            return 0;
        }

        if (batch.isEmpty()) {
            // 无数据：设备仍可达，清除历史错误状态。
            stats.clearError();
            return 0;
        }

        long advanced = 0;
        long runningTs = lastTs;
        for (RawReading raw : batch) {
            DeviceCollectorTask.Outcome outcome =
                    task.processOne(device, raw, normalizer, runningTs);

            if (outcome instanceof DeviceCollectorTask.Outcome.Accepted accepted) {
                var r = accepted.record();
                try {
                    spool.append(r.deviceId(), r.pointId(), r.dataType(), r.unit(),
                            r.numeric(), r.flag(), r.timestamp());
                } catch (SpoolFullException e) {
                    // 确定性背压：拒绝写入但保留最旧未确认数据；位点不推进，下轮重试。
                    stats.incrementBackpressure();
                    stats.recordError("SPOOL_FULL: " + e.getMessage());
                    log.warn("[SPOOL-BACKPRESSURE] device={} {} offset={} stays unchanged",
                            device.deviceId(), e.getMessage(), offset + advanced);
                    break;
                }
                stats.incrementAccepted(1);
                runningTs = r.timestamp();
            } else if (outcome instanceof DeviceCollectorTask.Outcome.Rejected rejected) {
                rejectionLog.append(rejected.rejected());
                stats.incrementRejected(rejected.rejected().reason());
            }

            // 接受（已入 spool）或拒绝（已落 rejection 日志）后才推进并持久化位点。
            advanced++;
            long newOffset = offset + advanced;
            checkpoints.save(device.deviceId(), newOffset, runningTs);
            stats.setOffset(newOffset);
            stats.clearError();
        }
        return advanced;
    }

    /** 各设备统计快照（设备标识 -> 统计）。 */
    public Map<String, DeviceStats> stats() {
        return Map.copyOf(contexts).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> e.getValue().stats()));
    }

    /** 是否已启动周期采集。 */
    public boolean isStarted() {
        return started;
    }
}

package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.buffer.AcceptResult;
import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferStateStore;
import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.collect.CollectionListener;
import com.github.highcumontoa.edgedatagatewayjava.collect.CollectorSupervisor;
import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStatus;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardListener;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardWorker;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthTransition;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizeResult;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;
import com.github.highcumontoa.edgedatagatewayjava.normalize.Normalizer;
import com.github.highcumontoa.edgedatagatewayjava.stats.Counters;
import com.github.highcumontoa.edgedatagatewayjava.stats.DeviceRuntime;
import com.github.highcumontoa.edgedatagatewayjava.stats.DeviceRuntimeState;
import com.github.highcumontoa.edgedatagatewayjava.stats.Reconciler;
import com.github.highcumontoa.edgedatagatewayjava.stats.ReconciliationReport;
import com.github.highcumontoa.edgedatagatewayjava.stats.RuntimeStateStore;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 边缘采集网关：组装采集调度、归一化、断网缓冲、顺序补传，并负责设备健康分级、
 * 运行时统计一致性核对与重启基线续算。
 *
 * <p>一致性要点：
 * <ul>
 *   <li>一把 {@link ReentrantReadWriteLock} 覆盖"健康状态机 + 全部计数器 +
 *       缓冲位点/pending"的变更与快照。锁序固定为 网关锁 → 缓冲锁，无反向获取，
 *       不会死锁；因此采集与补传并发进行时，{@link #snapshot()} 与
 *       {@link #reconcile()} 拿到的永远是同一状态版本的自洽结论。</li>
 *   <li>健康判定只依据本设备真实的轮询结果；上行中断/恢复不触碰健康状态机，
 *       因此断网不会把设备算成不健康。恢复后必须持续成功满观察窗口才回到正常。</li>
 *   <li>每次状态演进后原子落盘 {@code runtime-state.json}，重启后健康状态、
 *       连续失败/成功、最近错误与统计基线整体衔接；已确认/已按溢出处理的数据
 *       不再计入（WAL 只回放未确认记录），不重复计数。</li>
 * </ul>
 */
public final class EdgeGateway implements CollectionListener, ForwardListener {

    private static final Logger log = LoggerFactory.getLogger(EdgeGateway.class);

    private final GatewayProperties props;
    private final SpoolBuffer buffer;
    private final Normalizer normalizer;
    private final CollectorSupervisor supervisor;
    private final ForwardWorker forwarder;
    private final Counters counters;
    private final UplinkChannel channel;
    private final RuntimeStateStore runtimeStore;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 设备运行时（健康 + 计数）；初始化后结构不变，值在锁内变更。 */
    private final Map<String, DeviceRuntime> runtimes = new LinkedHashMap<>();

    public EdgeGateway(GatewayProperties props,
                       List<SimulatedDevice> devices,
                       UplinkChannel channel) throws IOException {
        this.props = props;
        this.channel = channel;
        Path dir = props.dataDir();
        Files.createDirectories(dir);
        this.buffer = new SpoolBuffer(
                new BufferStateStore.JsonFile(dir.resolve("buffer-state.json")),
                dir.resolve("spool.wal"),
                props.bufferCapacity(),
                props.overflowPolicy());
        buffer.recover();
        log.info("buffer recovered: nextSeq={}, confirmedSeq={}, pending={}",
                buffer.nextSeq(), buffer.confirmedSeq(), buffer.size());

        Map<String, PointDefinition> catalog = buildCatalog(devices);
        this.normalizer = new Normalizer(catalog);
        this.counters = new Counters();
        this.runtimeStore = new RuntimeStateStore.JsonFile(dir.resolve("runtime-state.json"));

        Map<String, DeviceRuntimeState> restored = runtimeStore.load();
        // 1) 本次在列设备：有采集线程
        for (SimulatedDevice d : devices) {
            DeviceRuntime rt = new DeviceRuntime(d.deviceId(), props.health());
            DeviceRuntimeState state = restored.get(d.deviceId());
            if (state != null) {
                rt.restore(state);
                log.info("runtime restored for device={}: health={}, cf={}, cs={}, accepted={}",
                        d.deviceId(), state.health(), state.consecutiveFailures(),
                        state.consecutiveSuccesses(), state.accepted());
            }
            runtimes.put(d.deviceId(), rt);
        }
        // 2) 基线文件里存在、但本次设备列表不再包含的设备：不启动采集线程，
        //    但其健康/计数基线仍恢复并参与全局守恒，WAL 中其未确认记录仍可被补传送达。
        for (Map.Entry<String, DeviceRuntimeState> e : restored.entrySet()) {
            if (runtimes.containsKey(e.getKey())) {
                continue;
            }
            DeviceRuntime rt = new DeviceRuntime(e.getKey(), props.health());
            rt.restore(e.getValue());
            rt.setOnline(false);
            runtimes.put(e.getKey(), rt);
            log.info("runtime restored for inactive device={} (baseline only, no collector)",
                    e.getKey());
        }
        restoreGlobalCounters();

        this.supervisor = new CollectorSupervisor(devices, this, props.collectIntervalMillis());
        this.forwarder = new ForwardWorker(buffer, channel, this,
                props.drainIntervalMillis(), props.batchSize());
    }

    /** 全局计数由全部已恢复设备的逐设备基线求和恢复（含本次不采集的历史设备）。 */
    private void restoreGlobalCounters() {
        long polled = 0, accepted = 0, delivered = 0, droppedOldest = 0, shedNewest = 0;
        Map<RejectReason, Long> rejected = new LinkedHashMap<>();
        for (RejectReason r : RejectReason.values()) {
            rejected.put(r, 0L);
        }
        for (DeviceRuntime rt : runtimes.values()) {
            var c = rt.counters();
            polled += c.polled();
            accepted += c.accepted();
            delivered += c.delivered();
            droppedOldest += c.droppedOldest();
            shedNewest += c.shedNewest();
            c.rejectedByReason().forEach((k, v) -> rejected.merge(k, v, Long::sum));
        }
        counters.restore(polled, accepted, delivered, droppedOldest, shedNewest, rejected);
    }

    public void start() {
        forwarder.start();
        supervisor.start();
        log.info("edge gateway started: {}", props);
    }

    public void shutdown() {
        // 先停采集，再停补传，关闭期间不再有新 append/confirm
        supervisor.stop();
        forwarder.stop();
        log.info("edge gateway stopped");
    }

    public SpoolBuffer buffer() {
        return buffer;
    }

    public Counters counters() {
        return counters;
    }

    public UplinkChannel channel() {
        return channel;
    }

    // ---- CollectionListener（在各设备采集线程上回调，进入网关写锁串行化）----
    // 计数演进在整个 poll 周期（begin→failure/success）内完成，运行时状态只在
    // 终态回调末尾落盘一次：避免"缓冲位点已推进、逐设备计数尚未更新"的中间版本
    // 被崩溃读到。因此 onPollBegin 不落盘。

    @Override
    public void onPollBegin(String deviceId, long nowMillis) {
        lock.writeLock().lock();
        try {
            DeviceRuntime rt = runtimes.get(deviceId);
            if (rt == null) {
                return;
            }
            rt.counters().recordPoll();
            rt.setLastPollAt(nowMillis);
            counters.incrementPolled();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void onPollFailure(String deviceId, Throwable error, long nowMillis) {
        lock.writeLock().lock();
        try {
            DeviceRuntime rt = runtimes.get(deviceId);
            if (rt == null) {
                return;
            }
            rt.counters().recordPollFailure();
            rt.setOnline(false);
            rt.noteError(nowMillis, String.valueOf(error));
            HealthTransition t = rt.health().recordFailure(String.valueOf(error), nowMillis);
            log.warn("[DEVICE-ERROR] device={} health={} error={}",
                    deviceId, rt.health().health(), error.toString());
            log.info("[DEVICE] device={} online=false", deviceId);
            if (t != null) {
                logHealth(deviceId, t);
            }
            persistRuntime();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void onPollSuccess(String deviceId, List<RawReading> readings, long nowMillis) {
        lock.writeLock().lock();
        boolean needKick = false;
        try {
            DeviceRuntime rt = runtimes.get(deviceId);
            if (rt == null) {
                return;
            }
            boolean wasOffline = !rt.online();
            rt.setOnline(true);
            HealthTransition t = rt.health().recordSuccess(nowMillis);
            if (wasOffline) {
                log.info("[DEVICE] device={} online=true", deviceId);
            }
            if (t != null) {
                logHealth(deviceId, t);
            }

            for (RawReading raw : readings) {
                NormalizeResult result;
                try {
                    result = normalizer.normalize(raw);
                } catch (Throwable ex) {
                    // 归一化内部不应抛出；万一抛出，按设备错误记录，绝不静默丢弃
                    rt.noteError(nowMillis, "normalizer failure: " + ex);
                    log.error("[DEVICE-ERROR] device={} normalizer failure for raw={}",
                            deviceId, raw, ex);
                    continue;
                }
                if (result.accepted()) {
                    needKick |= handleAccepted(rt, raw, result.record());
                } else {
                    handleRejected(rt, result.rejected());
                }
            }
            persistRuntime();
        } finally {
            lock.writeLock().unlock();
        }
        if (needKick) {
            forwarder.kick();
        }
    }

    /** 处理通过归一化的读数：乱序校验 → 入缓冲 → 溢出归属计数。返回是否需要唤醒补传。 */
    private boolean handleAccepted(DeviceRuntime rt, RawReading raw, NormalizedRecord record) {
        String deviceId = rt.deviceId();
        Long lastTs = buffer.lastTimestamp(deviceId);
        if (lastTs != null && raw.timestamp() < lastTs) {
            RejectedReading rr = new RejectedReading(raw, RejectReason.TIMESTAMP_OUT_OF_ORDER,
                    "timestamp " + raw.timestamp() + " < lastAccepted " + lastTs
                            + " for device " + deviceId);
            countRejected(rt, rr.reason());
            log.warn("[REJECT] raw={} reason={} detail={}", raw, rr.reason(), rr.detail());
            return false;
        }

        AcceptResult result = buffer.append(record);
        if (!result.accepted()) {
            // REJECT_NEWEST：缓冲满，新记录确定性拒绝；seq 已消耗形成可解释缺口
            counters.addShedNewest(1);
            rt.counters().recordShedNewest();
            log.warn("[BUFFER-FULL REJECT-NEWEST] raw={} normalized(seq={}) shed, "
                            + "policy={}, capacity={}, confirmedSeq={}",
                    raw, result.seq(), props.overflowPolicy(), props.bufferCapacity(),
                    buffer.confirmedSeq());
            return false;
        }

        counters.incrementAccepted();
        rt.counters().recordAccepted();
        NormalizedRecord stored = new NormalizedRecord(result.seq(), record.deviceId(),
                record.pointId(), record.type(), record.value(), record.unit(),
                record.timestamp());
        log.info("[ACCEPT] raw={} -> normalized={}", raw, stored);
        if (!result.dropped().isEmpty()) {
            int n = result.dropped().size();
            counters.addDroppedOldest(n);
            // 被弹出记录按设备归属，逐设备去向守恒（accepted = delivered+droppedOldest+pending）
            Map<String, Long> perDevice = new LinkedHashMap<>();
            result.dropped().forEach(v ->
                    perDevice.merge(v.deviceId(), 1L, Long::sum));
            perDevice.forEach((id, c) -> {
                DeviceRuntime victimRt = runtimes.get(id);
                if (victimRt != null) {
                    victimRt.counters().addDroppedOldest(c);
                }
            });
            result.dropped().forEach(v -> log.warn(
                    "[BUFFER-FULL DROP-OLDEST] evicted seq={} {}/{} ts={} to make room for seq={}",
                    v.seq(), v.deviceId(), v.pointId(), v.timestamp(), result.seq()));
        }
        return true;
    }

    private void handleRejected(DeviceRuntime rt, RejectedReading rejected) {
        countRejected(rt, rejected.reason());
        log.warn("[REJECT] raw={} reason={} detail={}",
                rejected.raw(), rejected.reason(), rejected.detail());
    }

    private void countRejected(DeviceRuntime rt, RejectReason reason) {
        counters.incrementRejected(reason);
        rt.counters().recordRejected(reason);
    }

    // ---- ForwardListener ----

    @Override
    public boolean onConfirmed(List<NormalizedRecord> batch, long ackSeq) {
        lock.writeLock().lock();
        try {
            long oldConfirmed = buffer.confirmedSeq();
            boolean advanced = buffer.confirm(ackSeq);
            if (!advanced) {
                return false;
            }
            long newConfirmed = buffer.confirmedSeq();
            // 只统计 (oldConfirmed, newConfirmed] 区间内、本批实际发送的记录
            Map<String, Long> perDevice = new LinkedHashMap<>();
            int total = 0;
            for (NormalizedRecord r : batch) {
                if (r.seq() > oldConfirmed && r.seq() <= newConfirmed) {
                    total++;
                    perDevice.merge(r.deviceId(), 1L, Long::sum);
                }
            }
            counters.incrementDelivered(total);
            perDevice.forEach((id, n) -> {
                DeviceRuntime rt = runtimes.get(id);
                if (rt != null) {
                    rt.counters().addDelivered(n);
                }
            });
            persistRuntime();
            log.info("[FORWARD] confirmedSeq={} (+{} records), nextSeq={}, pending={}",
                    newConfirmed, total, buffer.nextSeq(), buffer.size());
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void onUplinkDown(Throwable error) {
        // 上行通道事件：只记录通道状态，绝不参与设备健康判定
        log.warn("[UPLINK] down: {} — records spool locally, confirmedSeq={}, pending={}",
                error.toString(), buffer.confirmedSeq(), buffer.size());
    }

    @Override
    public void onUplinkUp() {
        log.info("[UPLINK] up — resume forwarding from confirmedSeq={}, pending={}",
                buffer.confirmedSeq(), buffer.size());
    }

    private void logHealth(String deviceId, HealthTransition t) {
        log.warn("[HEALTH] device={} transition: {} -> {} at={} reason={}",
                deviceId, t.from(), t.to(), t.atMillis(), t.reason());
    }

    /** 原子落盘全部设备运行时（在写锁内调用）。 */
    private void persistRuntime() {
        try {
            Map<String, DeviceRuntimeState> all = new LinkedHashMap<>();
            runtimes.forEach((id, rt) -> all.put(id, rt.snapshot()));
            runtimeStore.save(all);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist runtime state", e);
        }
    }

    // ---- 快照与核对（一致性读锁：同一状态版本）----

    public Map<String, DeviceStats> deviceStats() {
        lock.readLock().lock();
        try {
            Map<String, Long> pendingByDevice = pendingByDevice();
            Map<String, DeviceStats> out = new LinkedHashMap<>();
            runtimes.forEach((id, rt) -> {
                var c = rt.counters();
                long pending = pendingByDevice.getOrDefault(id, 0L);
                out.put(id, new DeviceStats(
                        id,
                        rt.online() ? DeviceStatus.UP : DeviceStatus.DOWN,
                        c.polled(),
                        c.failedPolls(),
                        c.accepted(),
                        c.rejectedTotal(),
                        rt.lastPollAt() == 0L ? null : rt.lastPollAt(),
                        rt.lastErrorAt() == 0L ? null : rt.lastErrorAt(),
                        rt.lastError(),
                        rt.health().health(),
                        rt.health().consecutiveFailures(),
                        rt.health().consecutiveSuccesses(),
                        rt.health().lastTransition(),
                        c.received(),
                        c.shedNewest(),
                        c.droppedOldest(),
                        c.delivered(),
                        pending));
            });
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private Map<String, Long> pendingByDevice() {
        Map<String, Long> pendingByDevice = new LinkedHashMap<>();
        for (NormalizedRecord r : buffer.pending()) {
            pendingByDevice.merge(r.deviceId(), 1L, Long::sum);
        }
        return pendingByDevice;
    }

    /** 仅生成核对结论（与 {@link #snapshot()} 同口径、同读锁）。 */
    public ReconciliationReport reconcile() {
        return buildSnapshot(System.currentTimeMillis()).reconciliation();
    }

    public GatewaySnapshot snapshot() {
        return buildSnapshot(System.currentTimeMillis());
    }

    private GatewaySnapshot buildSnapshot(long now) {
        lock.readLock().lock();
        try {
            Map<String, Long> pendingByDevice = pendingByDevice();
            Map<String, Reconciler.DeviceData> deviceData = new LinkedHashMap<>();
            Map<String, DeviceStats> stats = new LinkedHashMap<>();
            runtimes.forEach((id, rt) -> {
                var c = rt.counters();
                long pending = pendingByDevice.getOrDefault(id, 0L);
                deviceData.put(id, new Reconciler.DeviceData(
                        rt.online(), rt.health().health(),
                        c.polled(), c.failedPolls(), c.received(), c.accepted(),
                        c.shedNewest(), c.droppedOldest(), c.delivered(), pending,
                        c.rejectedByReason()));
                stats.put(id, new DeviceStats(
                        id, rt.online() ? DeviceStatus.UP : DeviceStatus.DOWN,
                        c.polled(), c.failedPolls(), c.accepted(), c.rejectedTotal(),
                        rt.lastPollAt() == 0L ? null : rt.lastPollAt(),
                        rt.lastErrorAt() == 0L ? null : rt.lastErrorAt(),
                        rt.lastError(),
                        rt.health().health(),
                        rt.health().consecutiveFailures(),
                        rt.health().consecutiveSuccesses(),
                        rt.health().lastTransition(),
                        c.received(), c.shedNewest(), c.droppedOldest(), c.delivered(),
                        pending));
            });

            var global = new Reconciler.GlobalData(
                    counters.polled(), counters.accepted(), counters.rejectedTotal(),
                    counters.delivered(), counters.droppedOldest(), counters.shedNewest(),
                    buffer.size());
            ReconciliationReport report = Reconciler.reconcile(now,
                    buffer.nextSeq(), buffer.confirmedSeq(), global, deviceData);

            return new GatewaySnapshot(
                    now,
                    channel.connected(),
                    buffer.nextSeq(),
                    buffer.confirmedSeq(),
                    buffer.size(),
                    counters.polled(),
                    counters.accepted(),
                    counters.delivered(),
                    counters.dropped(),
                    counters.rejectedByReason(),
                    stats,
                    report);
        } finally {
            lock.readLock().unlock();
        }
    }

    static Map<String, PointDefinition> buildCatalog(List<SimulatedDevice> devices) {
        Map<String, PointDefinition> catalog = new LinkedHashMap<>();
        for (SimulatedDevice d : devices) {
            for (PointDefinition p : d.points()) {
                String key = Normalizer.catalogKey(d.deviceId(), p.pointId());
                PointDefinition existing = catalog.get(key);
                if (existing != null && !existing.equals(p)) {
                    throw new IllegalArgumentException("conflicting point definitions for " + key);
                }
                catalog.put(key, p);
            }
        }
        return Map.copyOf(catalog);
    }
}

package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.audit.AuditReport;
import com.github.highcumontoa.edgedatagatewayjava.audit.ConsistencyAuditor;
import com.github.highcumontoa.edgedatagatewayjava.buffer.AcceptResult;
import com.github.highcumontoa.edgedatagatewayjava.buffer.BufferStateStore;
import com.github.highcumontoa.edgedatagatewayjava.buffer.SpoolBuffer;
import com.github.highcumontoa.edgedatagatewayjava.collect.AcceptOutcome;
import com.github.highcumontoa.edgedatagatewayjava.collect.CollectionListener;
import com.github.highcumontoa.edgedatagatewayjava.collect.CollectorSupervisor;
import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardListener;
import com.github.highcumontoa.edgedatagatewayjava.forward.ForwardWorker;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthMonitor;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;
import com.github.highcumontoa.edgedatagatewayjava.normalize.Normalizer;
import com.github.highcumontoa.edgedatagatewayjava.stats.DeviceStateStore;
import com.github.highcumontoa.edgedatagatewayjava.stats.StatsRegistry;
import com.github.highcumontoa.edgedatagatewayjava.uplink.UplinkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 边缘采集网关：组装采集调度、归一化、断网缓冲与顺序补传，
 * 以及设备健康分级、统计一致性核对与重启续算。
 *
 * <p>一致性要点：
 * <ul>
 *   <li>所有通过校验的记录先落缓冲（WAL + 位点状态持久化），再触发补传；
 *       重启后从已确认位点恢复，已确认数据不丢、未确认数据按序重放。</li>
 *   <li>每设备时间戳单调（允许相等，严格回退才拒绝）；位点状态持久化最后时间戳，
 *       重启后不会从错误时间点继续。</li>
 *   <li>畸形读数只带原因拒绝并计数，绝不静默丢弃或写入脏值。</li>
 *   <li>设备健康只由真实轮询结果驱动；上行中断不参与健康判定。</li>
 *   <li>统计与健康状态落盘（device-state.json），优雅停机后重启续算，
 *       不清零重算、不重复计数。</li>
 *   <li>统计口径：一条读数接受/拒绝/拒新三选一只计一次，逐台与整体同口径；
 *       与缓冲联动的计数在缓冲锁内更新，核对切面不被并发写撕裂。</li>
 * </ul>
 */
public final class EdgeGateway implements CollectionListener, ForwardListener {

    private static final Logger log = LoggerFactory.getLogger(EdgeGateway.class);

    private final GatewayProperties props;
    private final SpoolBuffer buffer;
    private final Normalizer normalizer;
    private final CollectorSupervisor supervisor;
    private final ForwardWorker forwarder;
    private final StatsRegistry stats;
    private final HealthMonitor health;
    private final ConsistencyAuditor auditor;
    private final DeviceStateStore deviceStateStore;
    private final UplinkChannel channel;

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
        this.stats = new StatsRegistry();
        // 与缓冲联动的统计在缓冲锁内更新：缓冲锁释放时统计与位点已同时生效，
        // 核对器的“视图→快照→视图”切面不会被并发写撕裂
        buffer.setMutationListener(new SpoolBuffer.MutationListener() {
            @Override
            public void onAppend(AcceptResult result, NormalizedRecord record) {
                if (result.accepted()) {
                    stats.recordAccepted(record.deviceId());
                    for (NormalizedRecord v : result.dropped()) {
                        stats.recordDroppedOldest(v.deviceId(), 1);
                    }
                } else {
                    stats.recordRejectedNewest(record.deviceId());
                }
            }

            @Override
            public void onConfirm(long confirmedSeq, List<NormalizedRecord> confirmed) {
                for (NormalizedRecord r : confirmed) {
                    stats.recordDelivered(r.deviceId());
                }
            }
        });
        buffer.recover();
        log.info("buffer recovered: nextSeq={}, confirmedSeq={}, pending={}",
                buffer.nextSeq(), buffer.confirmedSeq(), buffer.size());

        Map<String, PointDefinition> catalog = buildCatalog(devices);
        this.normalizer = new Normalizer(catalog);
        this.health = new HealthMonitor(props.degradeAfterFailures(),
                props.faultAfterFailures(), props.recoverAfterSuccesses());

        // 重启续算：恢复统计基线与健康状态（首次启动时文件不存在，从零开始）
        this.deviceStateStore = new DeviceStateStore(dir.resolve("device-state.json"));
        DeviceStateStore.PersistedState persisted = deviceStateStore.load();
        stats.restore(persisted.counters().values().stream().toList());
        health.restore(persisted.health().values().stream().toList());
        if (!persisted.counters().isEmpty() || !persisted.health().isEmpty()) {
            log.info("device state restored: devices={}, health={}",
                    persisted.counters().keySet(), persisted.health());
        }
        health.setTransitionListener(h -> {
            log.info("[HEALTH] device={} {} (reason={}, at={})", h.deviceId(), h.state(),
                    h.lastTransitionReason(), h.lastTransitionAt());
            persistDeviceState();
        });
        for (SimulatedDevice d : devices) {
            health.register(d.deviceId());
        }

        this.auditor = new ConsistencyAuditor(stats, buffer);
        this.supervisor = new CollectorSupervisor(devices, normalizer, catalog, this,
                props.collectIntervalMillis());
        // 采集侧运行计数衔接同一恢复基线，重启后逐台统计与整体统计不错位
        this.supervisor.restoreBaseline(persisted.counters().values().stream().toList());
        this.forwarder = new ForwardWorker(buffer, channel, this,
                props.drainIntervalMillis(), props.batchSize());
    }

    public void start() {
        forwarder.start();
        supervisor.start();
        log.info("edge gateway started: {}", props);
    }

    public void shutdown() {
        // 先停采集，再停补传，关闭期间不再有新 append
        supervisor.stop();
        forwarder.stop();
        persistDeviceState();
        log.info("edge gateway stopped");
    }

    private void persistDeviceState() {
        try {
            deviceStateStore.save(stats.persistedView(), health.persistedView());
        } catch (IOException e) {
            log.error("failed to persist device state", e);
        }
    }

    public SpoolBuffer buffer() {
        return buffer;
    }

    /** 统计登记处（兼容旧 Counters 只读接口）。 */
    public StatsRegistry counters() {
        return stats;
    }

    public UplinkChannel channel() {
        return channel;
    }

    public Map<String, DeviceStats> deviceStats() {
        return supervisor.snapshot();
    }

    /** 逐设备健康快照（含最近切换原因与时间）。 */
    public Map<String, DeviceHealth> deviceHealth() {
        return health.snapshot();
    }

    /** 运行中的统计一致性核对：返回一份自身自洽的核对结论。 */
    public AuditReport audit() {
        return auditor.audit();
    }

    public GatewaySnapshot snapshot() {
        return new GatewaySnapshot(
                System.currentTimeMillis(),
                channel.connected(),
                buffer.nextSeq(),
                buffer.confirmedSeq(),
                buffer.size(),
                stats.polled(),
                stats.accepted(),
                stats.delivered(),
                stats.dropped(),
                stats.rejectedByReason(),
                supervisor.snapshot(),
                health.snapshot());
    }

    // ---- CollectionListener（在各设备自己的采集线程上回调，缓冲自身线程安全）----

    @Override
    public void onPolled(String deviceId) {
        stats.recordPolled(deviceId);
    }

    @Override
    public void onPollSucceeded(String deviceId) {
        stats.recordPollSuccess(deviceId);
        health.onPollSuccess(deviceId);
    }

    @Override
    public AcceptOutcome onAccepted(String deviceId, RawReading raw, NormalizedRecord record) {
        Long lastTs = buffer.lastTimestamp(deviceId);
        if (lastTs != null && raw.timestamp() < lastTs) {
            RejectedReading rr = new RejectedReading(raw, RejectReason.TIMESTAMP_OUT_OF_ORDER,
                    "timestamp " + raw.timestamp() + " < lastAccepted " + lastTs
                            + " for device " + deviceId);
            stats.recordRejected(deviceId, RejectReason.TIMESTAMP_OUT_OF_ORDER);
            log.warn("[REJECT] raw={} reason={} detail={}", raw, rr.reason(), rr.detail());
            return AcceptOutcome.REJECTED_OUT_OF_ORDER;
        }

        AcceptResult result = buffer.append(record);
        // accepted / droppedOldest / rejectedNewest 统计已在缓冲锁内由 MutationListener 更新
        if (!result.accepted()) {
            // REJECT_NEWEST：缓冲满，新记录被确定性拒绝；seq 已消耗，形成可解释缺口
            log.warn("[BUFFER-FULL REJECT-NEWEST] raw={} normalized(seq={}) dropped, "
                            + "policy={}, capacity={}, confirmedSeq={}",
                    raw, result.seq(), props.overflowPolicy(), props.bufferCapacity(),
                    buffer.confirmedSeq());
            return AcceptOutcome.DROPPED_NEWEST;
        }

        NormalizedRecord stored = new NormalizedRecord(result.seq(), record.deviceId(),
                record.pointId(), record.type(), record.value(), record.unit(), record.timestamp());
        log.info("[ACCEPT] raw={} -> normalized={}", raw, stored);
        if (!result.dropped().isEmpty()) {
            for (NormalizedRecord v : result.dropped()) {
                log.warn("[BUFFER-FULL DROP-OLDEST] evicted seq={} {}/{} ts={} "
                                + "to make room for seq={}",
                        v.seq(), v.deviceId(), v.pointId(), v.timestamp(), result.seq());
            }
        }
        forwarder.kick();
        return AcceptOutcome.ACCEPTED;
    }

    @Override
    public void onRejected(String deviceId, RejectedReading rejected) {
        stats.recordRejected(deviceId, rejected.reason());
        log.warn("[REJECT] raw={} reason={} detail={}",
                rejected.raw(), rejected.reason(), rejected.detail());
    }

    @Override
    public void onDeviceError(String deviceId, Throwable error) {
        stats.recordPollFailure(deviceId, String.valueOf(error));
        health.onPollFailure(deviceId, error);
        log.warn("[DEVICE-ERROR] device={} error={}", deviceId, error.toString());
    }

    @Override
    public void onInternalError(String deviceId, RawReading raw, Throwable error) {
        stats.recordInternalError(deviceId);
        log.error("[INTERNAL-ERROR] device={} raw={} error={}", deviceId, raw,
                error.toString());
    }

    @Override
    public void onOnlineState(String deviceId, boolean online) {
        log.info("[DEVICE] device={} online={}", deviceId, online);
    }

    // ---- ForwardListener ----

    @Override
    public void onConfirmed(long confirmedSeq, int recordsThisBatch) {
        // delivered 统计已在缓冲锁内由 MutationListener 更新
        log.info("[FORWARD] confirmedSeq={} (+{} records), nextSeq={}, pending={}",
                confirmedSeq, recordsThisBatch, buffer.nextSeq(), buffer.size());
    }

    @Override
    public void onUplinkDown(Throwable error) {
        // 注意：上行中断不参与设备健康判定，只影响补传
        log.warn("[UPLINK] down: {} — records spool locally, confirmedSeq={}, pending={}",
                error.toString(), buffer.confirmedSeq(), buffer.size());
    }

    @Override
    public void onUplinkUp() {
        log.info("[UPLINK] up — resume forwarding from confirmedSeq={}, pending={}",
                buffer.confirmedSeq(), buffer.size());
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

    /** 溢出策略在日志中的引用（便于文档与代码对齐）。 */
    @SuppressWarnings("unused")
    private static final OverflowPolicy DEFAULT_POLICY = OverflowPolicy.DROP_OLDEST;
}

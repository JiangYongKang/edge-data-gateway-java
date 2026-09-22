package com.github.highcumontoa.edgedatagatewayjava.gateway;

import com.github.highcumontoa.edgedatagatewayjava.collect.CollectorSupervisor;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.replay.ReplayService;
import com.github.highcumontoa.edgedatagatewayjava.spool.DeviceCheckpointStore;
import com.github.highcumontoa.edgedatagatewayjava.spool.RejectionLog;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import com.github.highcumontoa.edgedatagatewayjava.upstream.UpstreamChannel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 网关编排者：装配并驱动各组件，定义统一的生命周期与一致性边界。
 *
 * <p>启动顺序（保证重启后位点与缓冲状态一致）：
 * <ol>
 *   <li>初始化并恢复 spool（未确认记录）、checkpoints（位点与时间戳）、rejection 日志；</li>
 *   <li>绑定设备（从 checkpoints 恢复各自 offset）与补传服务；</li>
 *   <li>若 autoStart，启动采集与补传。</li>
 * </ol>
 */
@Component
public class GatewayOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GatewayOrchestrator.class);

    private final GatewayProperties properties;
    private final CollectorSupervisor collectorSupervisor;
    private final ReplayService replayService;
    private final SpoolStore spool;
    private final DeviceCheckpointStore checkpoints;
    private final RejectionLog rejectionLog;
    private final UpstreamChannel channel;
    private final List<Device> devices;

    private volatile boolean running;

    public GatewayOrchestrator(GatewayProperties properties,
                               CollectorSupervisor collectorSupervisor,
                               ReplayService replayService,
                               SpoolStore spool,
                               DeviceCheckpointStore checkpoints,
                               RejectionLog rejectionLog,
                               UpstreamChannel channel,
                               List<Device> devices) {
        this.properties = properties;
        this.collectorSupervisor = collectorSupervisor;
        this.replayService = replayService;
        this.spool = spool;
        this.checkpoints = checkpoints;
        this.rejectionLog = rejectionLog;
        this.channel = channel;
        this.devices = devices;
    }

    /** 初始化持久层与设备、恢复状态，并按配置自动启动。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        Path dataDir = Path.of(properties.getDataDir());
        spool.init(dataDir, properties.getSpoolCapacity());
        checkpoints.init(dataDir);
        rejectionLog.init(dataDir);

        log.info("[GATEWAY-INIT] dataDir={} spoolPending={} nextSeq={} devices={}",
                dataDir.toAbsolutePath(), spool.pendingCount(), spool.nextSeq(),
                devices.stream().map(Device::deviceId).toList());

        collectorSupervisor.bind(devices, properties.getPollBatchSize(),
                properties.getCollectIntervalMs());
        replayService.bind(spool, channel);

        if (properties.isAutoStart()) {
            collectorSupervisor.startAll();
            replayService.start(properties.getReplayIntervalMs());
        }
        running = true;
        log.info("[GATEWAY-STARTED] autoStart={} collectIntervalMs={} replayIntervalMs={}",
                properties.isAutoStart(), properties.getCollectIntervalMs(),
                properties.getReplayIntervalMs());
    }

    /** 停止采集与补传（持久状态保留，重启可继续）。 */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        collectorSupervisor.stopAll();
        replayService.stop();
        running = false;
        log.info("[GATEWAY-STOPPED] spoolPending={} nextSeq={}",
                spool.pendingCount(), spool.nextSeq());
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public CollectorSupervisor collector() {
        return collectorSupervisor;
    }

    public ReplayService replay() {
        return replayService;
    }

    public SpoolStore spool() {
        return spool;
    }

    public DeviceCheckpointStore checkpoints() {
        return checkpoints;
    }

    public RejectionLog rejectionLog() {
        return rejectionLog;
    }

    public UpstreamChannel channel() {
        return channel;
    }
}

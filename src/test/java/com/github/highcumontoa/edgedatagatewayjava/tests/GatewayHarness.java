package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.collect.CollectorSupervisor;
import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceCollectorTask;
import com.github.highcumontoa.edgedatagatewayjava.device.Device;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewayOrchestrator;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.normalize.ReadingNormalizer;
import com.github.highcumontoa.edgedatagatewayjava.replay.ReplayService;
import com.github.highcumontoa.edgedatagatewayjava.spool.DeviceCheckpointStore;
import com.github.highcumontoa.edgedatagatewayjava.spool.RejectionLog;
import com.github.highcumontoa.edgedatagatewayjava.spool.SpoolStore;
import com.github.highcumontoa.edgedatagatewayjava.upstream.MockUpstreamChannel;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试装配工具：手动 new 全部组件，不启动 Spring/Web，autoStart=false，
 * 采集与补传由测试显式驱动，保证确定性。
 */
class GatewayHarness implements AutoCloseable {

    final GatewayProperties props;
    final SpoolStore spool;
    final DeviceCheckpointStore checkpoints;
    final RejectionLog rejectionLog;
    final MockUpstreamChannel channel;
    final CollectorSupervisor collector;
    final ReplayService replay;
    final GatewayOrchestrator orchestrator;
    private final List<Device> devices;

    GatewayHarness(Path dataDir, int capacity, List<Device> devices) {
        this.devices = devices;
        props = new GatewayProperties();
        props.setDataDir(dataDir.toString());
        props.setSpoolCapacity(capacity);
        props.setPollBatchSize(5);
        props.setAutoStart(false);

        spool = new SpoolStore();
        checkpoints = new DeviceCheckpointStore();
        rejectionLog = new RejectionLog();
        channel = new MockUpstreamChannel();

        ReadingNormalizer normalizer = new ReadingNormalizer();
        DeviceCollectorTask task = new DeviceCollectorTask();
        collector = new CollectorSupervisor(
                normalizer, task, spool, checkpoints, rejectionLog);
        replay = new ReplayService();

        orchestrator = new GatewayOrchestrator(
                props, collector, replay, spool, checkpoints,
                rejectionLog, channel, devices);
        orchestrator.start();
    }

    /** 反复采集所有设备直到全部脚本耗尽（背压时某些设备会暂时无推进）。 */
    void drainAll() {
        int guard = 100_000;
        boolean any;
        do {
            any = false;
            for (Device d : devices) {
                if (collector.collectOnce(d.deviceId()) > 0) {
                    any = true;
                }
            }
        } while (any && guard-- > 0);
    }

    @Override
    public void close() {
        orchestrator.stop();
    }
}

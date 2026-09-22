package com.github.highcumontoa.edgedatagatewayjava.web;

import com.github.highcumontoa.edgedatagatewayjava.collect.DeviceStats;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewayOrchestrator;
import com.github.highcumontoa.edgedatagatewayjava.upstream.MockUpstreamChannel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 运维接口：
 * <ul>
 *   <li>{@code GET  /api/gateway/status} 查看在线状态、缓冲位点与各设备统计；</li>
 *   <li>{@code POST /api/gateway/channel?state=offline|online} 模拟断网/恢复；</li>
 *   <li>{@code POST /api/gateway/collect?deviceId=} 立即采集一个周期；</li>
 *   <li>{@code POST /api/gateway/flush} 立即补传一轮。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayOrchestrator orchestrator;

    public GatewayController(GatewayOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/status")
    public GatewayStatus status() {
        var spool = orchestrator.spool();
        var collector = orchestrator.collector();
        var replay = orchestrator.replay();
        var channel = orchestrator.channel();

        List<GatewayStatus.DeviceStatusView> devices = collector.stats().values().stream()
                .sorted(Comparator.comparing(DeviceStats::deviceId))
                .map(s -> new GatewayStatus.DeviceStatusView(
                        s.deviceId(), s.isOnline(), s.offset(),
                        s.acceptedCount(), s.rejectedCount(),
                        s.spoolBackpressureCount(), s.consecutiveErrors(), s.lastError()))
                .toList();

        return new GatewayStatus(
                orchestrator.isRunning(),
                channel.isOnline(),
                spool.pendingCount(),
                spool.capacity(),
                spool.nextSeq(),
                channel instanceof MockUpstreamChannel m ? m.deliveredCount() : -1,
                devices);
    }

    /** 模拟断网（offline）或恢复（online）。 */
    @PostMapping("/channel")
    public GatewayStatus channel(@RequestParam String state) {
        var channel = orchestrator.channel();
        if (channel instanceof MockUpstreamChannel mock) {
            if ("offline".equalsIgnoreCase(state)) {
                mock.disconnect();
            } else if ("online".equalsIgnoreCase(state)) {
                mock.reconnect();
            } else {
                throw new IllegalArgumentException("state must be offline or online");
            }
        }
        return status();
    }

    /** 立即触发指定设备一个采集周期。 */
    @PostMapping("/collect")
    public GatewayStatus collect(@RequestParam String deviceId) {
        orchestrator.collector().collectOnce(deviceId);
        return status();
    }

    /** 立即执行一轮补传。 */
    @PostMapping("/flush")
    public GatewayStatus flush() {
        orchestrator.replay().flushOnce();
        return status();
    }
}

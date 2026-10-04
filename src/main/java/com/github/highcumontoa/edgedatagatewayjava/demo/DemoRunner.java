package com.github.highcumontoa.edgedatagatewayjava.demo;

import com.github.highcumontoa.edgedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.edgedatagatewayjava.device.ScriptedDevice;
import com.github.highcumontoa.edgedatagatewayjava.device.SimulatedDevice;
import com.github.highcumontoa.edgedatagatewayjava.gateway.EdgeGateway;
import com.github.highcumontoa.edgedatagatewayjava.gateway.GatewaySnapshot;
import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.uplink.MockUplinkChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 本地可运行演示。
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.github.highcumontoa.edgedatagatewayjava.demo.DemoRunner
 * </pre>
 *
 * 演示剧本：两个设备并发采集 → 上行断网 2 秒（本地缓冲、触发溢出）→ 恢复顺序补传 →
 * 一台设备故障再恢复。日志含 [ACCEPT]/[REJECT]/[BUFFER-FULL]/[FORWARD]/[UPLINK] 等标记。
 */
public final class DemoRunner {

    private DemoRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("edge-gateway-demo-");
        System.out.println("[DEMO] data dir: " + dir);

        PointDefinition temp = new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
        PointDefinition rpm = new PointDefinition("rpm", DataType.INT, "rpm", 0.0, 10000.0);
        PointDefinition running = new PointDefinition("running", DataType.BOOLEAN, "", null, null);
        PointDefinition pressure = new PointDefinition("pressure", DataType.DOUBLE, "kPa", 0.0, 1600.0);

        AtomicLong clock = new AtomicLong(1_000_000L);

        // 设备 A：正常 + 周期性产出各种畸形读数
        List<Supplier<List<RawReading>>> scriptA = List.of(
                () -> List.of(raw("dev-A", "temp", 21.5, "C", clock.addAndGet(10))),
                () -> List.of(raw("dev-A", "rpm", 3200, "rpm", clock.addAndGet(10))),
                () -> List.of(raw("dev-A", "temp", 200.0, "C", clock.addAndGet(10))),   // 越界
                () -> List.of(raw("dev-A", "rpm", 3200, "r/min", clock.addAndGet(10))),// 单位不匹配
                () -> List.of(raw("dev-A", "temp", "21.5", "C", clock.addAndGet(10))), // 类型不匹配
                () -> List.of(raw("dev-A", "temp", 22.1, "C", null)),                  // 时间戳缺失
                () -> List.of(raw("dev-A", "running", true, "", clock.addAndGet(10))),
                () -> List.of(raw("dev-A", "temp", 23.0, "C", clock.addAndGet(-20)))   // 乱序
        );
        // 设备 B：正常读数，故障在主线程通过 setOnline 控制
        List<Supplier<List<RawReading>>> scriptB = List.of(
                () -> List.of(raw("dev-B", "pressure", 101.3, "kPa", clock.addAndGet(10))),
                () -> List.of(raw("dev-B", "pressure", 102.7, "kPa", clock.addAndGet(10))),
                () -> List.of(raw("dev-B", "pressure", 99.9, "kPa", clock.addAndGet(10)))
        );

        ScriptedDevice a = new ScriptedDevice("dev-A", List.of(temp, rpm, running), scriptA, true, true);
        ScriptedDevice b = new ScriptedDevice("dev-B", List.of(pressure), scriptB, true, true);

        MockUplinkChannel channel = new MockUplinkChannel(true);
        GatewayProperties props = new GatewayProperties(dir, 8, OverflowPolicy.DROP_OLDEST,
                200L, 100L, 50);
        EdgeGateway gateway = new EdgeGateway(props, List.of(a, b), channel);
        gateway.start();

        // 第 0.5 秒断网，持续 2 秒（采集继续，缓冲积压直至溢出 DROP_OLDEST）
        sleep(500);
        System.out.println("[DEMO] >>> disconnect uplink");
        channel.disconnect();
        sleep(2000);
        System.out.println("[DEMO] >>> reconnect uplink (orderly replay expected)");
        channel.reconnect();
        sleep(800);

        // 设备 B 掉线 1 秒后恢复，设备 A 不受影响
        System.out.println("[DEMO] >>> dev-B goes offline");
        b.setOnline(false);
        sleep(1000);
        System.out.println("[DEMO] >>> dev-B back online");
        b.setOnline(true);
        sleep(1200);

        gateway.shutdown();

        GatewaySnapshot s = gateway.snapshot();
        System.out.println("[DEMO] final snapshot: connected=" + s.uplinkConnected()
                + " nextSeq=" + s.nextSeq() + " confirmedSeq=" + s.confirmedSeq()
                + " pending=" + s.pendingCount());
        System.out.println("[DEMO] counters: polled=" + s.polled()
                + " accepted=" + s.accepted() + " delivered=" + s.delivered()
                + " dropped=" + s.dropped() + " rejectedByReason=" + s.rejectedByReason());
        System.out.println("[DEMO] downstream records (dedup, seq ascending): "
                + channel.delivered().stream().map(r -> r.seq() + "/" + r.deviceId()
                + "/" + r.pointId() + "=" + r.value()).toList());
        System.out.println("[DEMO] devices: " + s.devices());
        s.health().forEach((id, h) -> System.out.println("[DEMO] health " + id
                + ": state=" + h.state()
                + " consecFail=" + h.consecutiveFailures()
                + " consecSucc=" + h.consecutiveSuccesses()
                + " lastTransition=" + h.lastTransitionReason()
                + " at=" + h.lastTransitionAt()
                + " lastError=" + h.lastError()));
        var audit = gateway.audit();
        System.out.println("[DEMO] audit: status=" + audit.status()
                + " violations=" + audit.violations());
    }

    private static RawReading raw(String device, String point, Object value,
                                  String unit, Long ts) {
        return new RawReading(device, point, value, unit, ts);
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** 通用条件等待：轮询直到条件成立或超时（超时抛 AssertionError）。 */
    public static void await(java.util.function.BooleanSupplier cond, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition not met within " + timeoutMillis + "ms");
        }
    }

    /** 演示中创建的模拟设备类型引用，避免无用 import 检查告警。 */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP = SimulatedDevice.class;
}

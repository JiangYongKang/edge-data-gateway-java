package com.github.highcumontoa.edgedatagatewayjava.device;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造两台确定性的本地演示设备。
 *
 * <p>脚本中刻意编排了各类畸形读数，以演示可区分的拒绝原因；
 * 正常读数的时间戳取自固定基准（非 System.currentTimeMillis），保证可复现。
 */
public final class DemoDeviceFactory {

    /** 固定基准时间：2026-09-23T00:00:00Z。 */
    public static final long BASE_TS = 1_790_000_000_000L;

    private DemoDeviceFactory() {
    }

    public static List<Device> create() {
        return List.of(createDeviceA(), createDeviceB());
    }

    /** 设备A：温度/湿度（DOUBLE）与运行状态（BOOLEAN），含各类畸形读数。 */
    public static MockDevice createDeviceA() {
        Map<String, PointDefinition> points = new LinkedHashMap<>();
        points.put("temperature",
                new PointDefinition("temperature", DataType.DOUBLE, "C", -40.0, 85.0));
        points.put("humidity",
                new PointDefinition("humidity", DataType.DOUBLE, "%", 0.0, 100.0));
        points.put("running",
                new PointDefinition("running", DataType.BOOLEAN, "bool", null, null));

        String d = "device-A";
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        long t = BASE_TS;
        // 正常读数
        script.add(entry(new RawReading(d, "temperature", 21.5, "C", t)));
        script.add(entry(new RawReading(d, "humidity", 55.0, "%", t)));
        script.add(entry(new RawReading(d, "running", Boolean.TRUE, "bool", t)));
        // 越界
        script.add(entry(new RawReading(d, "temperature", 120.0, "C", t + 1000)));
        // 值缺失
        script.add(entry(new RawReading(d, "humidity", null, "%", t + 2000)));
        // 类型不匹配
        script.add(entry(new RawReading(d, "running", "true", "bool", t + 3000)));
        // 单位不匹配
        script.add(entry(new RawReading(d, "temperature", 22.0, "F", t + 4000)));
        // 未知点位
        script.add(entry(new RawReading(d, "vibration", 0.1, "g", t + 5000)));
        // 时间戳缺失
        script.add(entry(new RawReading(d, "humidity", 60.0, "%", null)));
        // 时间戳乱序（严格回退到已接受时间戳之前）
        script.add(entry(new RawReading(d, "temperature", 20.0, "C", t - 1000)));
        // 恢复正常（时间戳继续向前）
        script.add(entry(new RawReading(d, "temperature", 23.0, "C", t + 6000)));
        script.add(entry(new RawReading(d, "humidity", 57.0, "%", t + 6000)));
        return new MockDevice(d, points, script);
    }

    /** 设备B：压力与阀门状态，正常数据为主，用于多设备并发验证。 */
    public static MockDevice createDeviceB() {
        Map<String, PointDefinition> points = new LinkedHashMap<>();
        points.put("pressure",
                new PointDefinition("pressure", DataType.DOUBLE, "kPa", 0.0, 1000.0));
        points.put("valve",
                new PointDefinition("valve", DataType.BOOLEAN, "bool", null, null));

        String d = "device-B";
        List<MockDevice.ScriptEntry> script = new ArrayList<>();
        long t = BASE_TS;
        for (int i = 0; i < 8; i++) {
            script.add(entry(new RawReading(d, "pressure", 100.0 + i, "kPa", t + i * 1000L)));
            script.add(entry(new RawReading(d, "valve", i % 2 == 0, "bool", t + i * 1000L)));
        }
        // 一条越界、一条低位越界
        script.add(entry(new RawReading(d, "pressure", 1500.0, "kPa", t + 8000)));
        script.add(entry(new RawReading(d, "pressure", -5.0, "kPa", t + 9000)));
        return new MockDevice(d, points, script);
    }

    private static MockDevice.ScriptEntry entry(RawReading r) {
        return new MockDevice.ScriptEntry(r);
    }
}

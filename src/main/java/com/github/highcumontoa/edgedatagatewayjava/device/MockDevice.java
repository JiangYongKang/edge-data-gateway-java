package com.github.highcumontoa.edgedatagatewayjava.device;

import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 脚本化的本地模拟设备：按预编排脚本逐条产出读数（可含畸形读数）。
 *
 * <p>脚本内容与时间戳均为确定值，offset 语义为“已消费条数”，
 * poll(offset, maxBatch) 返回脚本中 [offset, offset+maxBatch) 的切片，
 * 超过脚本长度返回空列表（设备数据耗尽）。
 */
public class MockDevice implements Device {

    /** 一条脚本项即一条原始读数。 */
    public record ScriptEntry(RawReading reading) {
    }

    private final String deviceId;
    private final Map<String, PointDefinition> points;
    private final List<RawReading> script;

    public MockDevice(String deviceId,
                      Map<String, PointDefinition> points,
                      List<ScriptEntry> script) {
        this.deviceId = deviceId;
        this.points = Map.copyOf(points);
        List<RawReading> list = new ArrayList<>(script.size());
        for (ScriptEntry e : script) {
            list.add(e.reading());
        }
        this.script = List.copyOf(list);
    }

    @Override
    public String deviceId() {
        return deviceId;
    }

    @Override
    public Map<String, PointDefinition> points() {
        return points;
    }

    @Override
    public List<RawReading> poll(long offset, int maxBatch) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        int start = (int) Math.min(offset, script.size());
        int end = (int) Math.min(start + (long) maxBatch, script.size());
        if (start >= end) {
            return List.of();
        }
        return new ArrayList<>(script.subList(start, end));
    }

    /** 脚本总条数（测试中用于等待设备耗尽）。 */
    public int scriptSize() {
        return script.size();
    }
}

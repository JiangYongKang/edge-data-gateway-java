package com.github.highcumontoa.edgedatagatewayjava.device;

import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.List;
import java.util.Map;

/** 本地可采集的现场设备抽象。 */
public interface Device {

    /** 设备标识。 */
    String deviceId();

    /** 设备点位表：pointId -> 点位定义。 */
    Map<String, PointDefinition> points();

    /**
     * 从给定采集位点（已采集条数）读取一批原始读数。
     * 实现应确定性地产生读数（可含畸形读数用于测试）。
     *
     * @param offset 采集位点
     * @param maxBatch 最多返回条数
     * @return 原始读数列表（可能为空）
     */
    List<RawReading> poll(long offset, int maxBatch);
}

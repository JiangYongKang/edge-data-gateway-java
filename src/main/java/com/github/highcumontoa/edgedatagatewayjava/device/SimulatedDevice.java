package com.github.highcumontoa.edgedatagatewayjava.device;

import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.List;

/** 本地模拟现场设备：按脚本产出原始读数。 */
public interface SimulatedDevice {
    String deviceId();

    List<PointDefinition> points();

    /** 读取一批读数（实现可以产出含畸形/越界/乱序等异常读数以便测试）。 */
    List<RawReading> poll();

    /** 是否在线。 */
    boolean online();
}

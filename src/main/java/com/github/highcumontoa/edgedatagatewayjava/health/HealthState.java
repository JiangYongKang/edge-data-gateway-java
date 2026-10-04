package com.github.highcumontoa.edgedatagatewayjava.health;

/**
 * 设备健康分级（只由设备自身轮询结果驱动，与上行通道状态无关）。
 *
 * <ul>
 *   <li>{@link #HEALTHY}：正常，连续采集稳定。</li>
 *   <li>{@link #DEGRADED}：反复出错（连续失败达到降级阈值），尚未判定严重故障。</li>
 *   <li>{@link #FAULT}：已判定严重故障（连续失败达到故障阈值）。</li>
 *   <li>{@link #RECOVERING}：故障后恢复观察中——已重新采集成功，但连续成功次数
 *       尚未达到恢复阈值，避免刚恢复一次就被当成完全正常。</li>
 * </ul>
 */
public enum HealthState {
    HEALTHY,
    DEGRADED,
    FAULT,
    RECOVERING
}

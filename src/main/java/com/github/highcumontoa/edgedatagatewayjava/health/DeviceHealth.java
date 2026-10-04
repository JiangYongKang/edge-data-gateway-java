package com.github.highcumontoa.edgedatagatewayjava.health;

/**
 * 设备健康分级（与"在线/离线"连通性正交）。
 *
 * <ul>
 *   <li>{@link #HEALTHY}：正常，轮询连续稳定；</li>
 *   <li>{@link #DEGRADED}：反复出错（连续失败达到降级阈值但未到严重故障阈值）；</li>
 *   <li>{@link #CRITICAL}：已判定严重故障（连续失败达到严重故障阈值）；</li>
 *   <li>{@link #RECOVERING}：故障后正在恢复观察中，连续成功需持续满观察窗口才回到正常。</li>
 * </ul>
 *
 * <p>健康判定只依据本设备自己真实的轮询结果；上行通道中断不参与健康判定。
 */
public enum DeviceHealth {
    HEALTHY,
    DEGRADED,
    CRITICAL,
    RECOVERING
}

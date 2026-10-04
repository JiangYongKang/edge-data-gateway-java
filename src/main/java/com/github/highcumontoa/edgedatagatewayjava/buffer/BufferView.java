package com.github.highcumontoa.edgedatagatewayjava.buffer;

import java.util.Map;

/**
 * 缓冲位点的一致性视图（在缓冲锁内生成）。
 *
 * <p>统计核对用它与统计快照交叉验证：同一视图被读取两次且相等，
 * 说明两次读取之间没有任何 append/confirm，统计计数中与缓冲联动的
 * 字段（accepted/delivered/dropped）在该窗口内也未变化。
 */
public record BufferView(
        long nextSeq,
        long confirmedSeq,
        int pending,
        Map<String, Integer> pendingByDevice
) {
    public BufferView {
        pendingByDevice = Map.copyOf(pendingByDevice);
    }
}

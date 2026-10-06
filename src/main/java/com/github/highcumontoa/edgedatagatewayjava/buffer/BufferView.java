package com.github.highcumontoa.edgedatagatewayjava.buffer;

import java.util.Map;

/**
 * 缓冲位点的一致性视图（在缓冲锁内生成）。
 *
 * <p>统计核对在 ConsistencyGate 读锁内把它与统计快照一起读出：
 * 二者来自同一逻辑瞬间，可安全交叉验证（序号守恒、流向守恒）。
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

package com.github.highcumontoa.edgedatagatewayjava.forward;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.List;

/** 补传事件回调。 */
public interface ForwardListener {

    /**
     * 一批记录发送成功：由实现方在自身一致性写锁内原子地推进确认位点、
     * 按设备归属计数并持久化基线。
     *
     * @param batch       本批实际发送（已被下游幂等去重）的记录
     * @param ackSeq      通道返回的确认序号（可能大于本应确认的位点，由实现方裁剪）
     * @return 位点是否真正推进（重复/回退确认返回 false，不计数、不打位点日志）
     */
    boolean onConfirmed(List<NormalizedRecord> batch, long ackSeq);

    /** 上行中断（不影响设备健康判定）。 */
    void onUplinkDown(Throwable error);

    /** 上行恢复。 */
    void onUplinkUp();
}

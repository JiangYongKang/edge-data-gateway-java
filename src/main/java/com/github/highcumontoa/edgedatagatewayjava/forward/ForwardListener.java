package com.github.highcumontoa.edgedatagatewayjava.forward;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.List;

/** 补传事件回调。 */
public interface ForwardListener {
    /** 位点推进（幂等：只在真正推进时调用一次）。 */
    void onConfirmed(long confirmedSeq, int recordsThisBatch);

    /** 本批实际确认的记录明细（用于逐设备送达统计）；默认忽略。 */
    default void onConfirmedRecords(List<NormalizedRecord> confirmed) {
    }

    /** 上行中断。 */
    void onUplinkDown(Throwable error);

    /** 上行恢复。 */
    void onUplinkUp();
}

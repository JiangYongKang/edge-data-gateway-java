package com.github.highcumontoa.edgedatagatewayjava.forward;

/** 补传事件回调。 */
public interface ForwardListener {
    /** 位点推进（幂等：只在真正推进时调用一次）。 */
    void onConfirmed(long confirmedSeq, int recordsThisBatch);

    /** 上行中断。 */
    void onUplinkDown(Throwable error);

    /** 上行恢复。 */
    void onUplinkUp();
}

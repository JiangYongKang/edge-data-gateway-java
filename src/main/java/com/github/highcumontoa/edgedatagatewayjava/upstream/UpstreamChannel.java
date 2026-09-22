package com.github.highcumontoa.edgedatagatewayjava.upstream;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.List;

/** 上行通道抽象。断网与恢复由实现控制，便于本地模拟。 */
public interface UpstreamChannel {

    /** 通道当前是否在线（可投递）。 */
    boolean isOnline();

    /**
     * 投递一批记录。仅在下游真正持久化成功后返回确认的序列号；
     * 实现必须对同一 seq 幂等去重，重复投递不得产生重复下游记录。
     * 通道不可用时抛 {@link ChannelException}，此时缓冲不确认任何记录。
     */
    List<Long> deliver(List<NormalizedRecord> batch);
}

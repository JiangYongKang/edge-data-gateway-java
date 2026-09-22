package com.github.highcumontoa.edgedatagatewayjava.uplink;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.util.List;

/** 上行通道抽象。 */
public interface UplinkChannel {
    /**
     * 投递一批按 seq 升序的记录。必须幂等：重复投递同一 seq 不得产生重复下游记录。
     * @return 已被下游接受的最大 seq；全部失败/断网时抛 UplinkException
     */
    long send(List<NormalizedRecord> batch) throws UplinkException;

    boolean connected();
}

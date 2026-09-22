package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;

/** 采集事件回调（由网关实现）。单设备异常不得影响其它设备。 */
public interface CollectionListener {
    /** 完成一次设备轮询（无论成功或异常），用于全局轮询计数与在线状态。 */
    void onPolled(String deviceId);

    /** 归一化通过（record.seq 尚未分配，由缓冲分配；网关在此做每设备时间戳乱序判断）。 */
    void onAccepted(String deviceId, RawReading raw, NormalizedRecord record);

    /** 归一化拒绝（带可区分原因）。 */
    void onRejected(String deviceId, RejectedReading rejected);

    /** 设备轮询异常。 */
    void onDeviceError(String deviceId, Throwable error);

    /** 设备在线状态变更。 */
    void onOnlineState(String deviceId, boolean online);
}

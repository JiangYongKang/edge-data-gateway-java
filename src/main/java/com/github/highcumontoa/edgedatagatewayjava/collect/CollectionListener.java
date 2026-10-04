package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;

import java.util.List;

/**
 * 采集事件回调（由网关实现）。
 *
 * <p>所有回调都在对应设备自己的采集线程上触发；网关在自身的一致性写锁内完成
 * 健康判定、归一化、入缓冲与全部计数，保证这些状态作为一个原子版本演进。
 * 单设备轮询异常由调度器在设备线程内隔离，不会传播到其它设备。
 */
public interface CollectionListener {

    /** 一次轮询开始（无论随后成功或异常）：计入轮询次数与最后轮询时间。 */
    void onPollBegin(String deviceId, long nowMillis);

    /** 设备轮询抛出异常：这是本设备"真实的失败"，参与健康降级判定。 */
    void onPollFailure(String deviceId, Throwable error, long nowMillis);

    /** 设备轮询成功并返回一批原始读数（可能为空批）。 */
    void onPollSuccess(String deviceId, List<RawReading> readings, long nowMillis);
}

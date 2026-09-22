package com.github.highcumontoa.edgedatagatewayjava.collect;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;

/**
 * 一次采集产生的已接受读数（尚未分配全局 seq），
 * 由编排层负责入 spool；携带位点推进所需的时间戳。
 */
public record AcceptedRecord(
        String deviceId,
        String pointId,
        DataType dataType,
        String unit,
        Double numeric,
        Boolean flag,
        long timestamp
) {
}

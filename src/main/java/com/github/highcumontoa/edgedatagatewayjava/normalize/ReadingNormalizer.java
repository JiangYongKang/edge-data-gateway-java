package com.github.highcumontoa.edgedatagatewayjava.normalize;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 读数归一化器：按点位定义校验并转换原始读数。
 *
 * <p>校验顺序固定（决定拒绝原因的优先级，保证同一读数在异常/并发场景结论一致）：
 * <ol>
 *   <li>点位存在性 {@code UNKNOWN_POINT}</li>
 *   <li>值缺失 {@code VALUE_MISSING}</li>
 *   <li>类型匹配 {@code TYPE_MISMATCH}</li>
 *   <li>单位匹配 {@code UNIT_MISMATCH}</li>
 *   <li>数值量程 {@code OUT_OF_RANGE}</li>
 *   <li>时间戳缺失 {@code TIMESTAMP_MISSING}</li>
 *   <li>时间戳顺序 {@code TIMESTAMP_OUT_OF_ORDER}（同一设备，时间戳须非递减；
 *       同一扫描周期内不同点位时间戳相等属正常，仅严格回退才拒绝）</li>
 * </ol>
 *
 * <p>本类无状态；“最近一次已接受时间戳”由调用方持有（持久化到位点），
 * 因此重启后乱序判定仍然一致。
 */
@Component
public class ReadingNormalizer {

    /**
     * 归一化一条原始读数。
     *
     * @param raw                 原始读数
     * @param points              该设备的点位表
     * @param lastAcceptedTimestamp 同一设备最近一次已接受读数时间戳；&lt;0 表示尚无
     * @return 不含全局序列号的归一化记录（seq=0，由 spool 分配）
     * @throws NormalizationException 任一校验失败，携带可区分原因
     */
    public NormalizedRecord normalize(RawReading raw,
                                      Map<String, PointDefinition> points,
                                      long lastAcceptedTimestamp) {
        PointDefinition point = points.get(raw.pointId());
        if (point == null) {
            throw new NormalizationException(
                    com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.UNKNOWN_POINT,
                    "device " + raw.deviceId() + " has no point " + raw.pointId());
        }

        Object value = raw.value();
        if (value == null) {
            throw new NormalizationException(
                    com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.VALUE_MISSING,
                    "point " + point.pointId() + " value is null");
        }

        Double numeric = null;
        Boolean flag = null;
        if (point.dataType() == DataType.DOUBLE) {
            numeric = toDouble(value);
            if (numeric == null) {
                throw new NormalizationException(
                        com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.TYPE_MISMATCH,
                        "point " + point.pointId() + " expects DOUBLE but got "
                                + value.getClass().getSimpleName() + "=" + value);
            }
        } else if (point.dataType() == DataType.BOOLEAN) {
            flag = toBoolean(value);
            if (flag == null) {
                throw new NormalizationException(
                        com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.TYPE_MISMATCH,
                        "point " + point.pointId() + " expects BOOLEAN but got "
                                + value.getClass().getSimpleName() + "=" + value);
            }
        }

        if (raw.unit() == null || !raw.unit().equals(point.unit())) {
            throw new NormalizationException(
                    com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.UNIT_MISMATCH,
                    "point " + point.pointId() + " expects unit '" + point.unit()
                            + "' but got '" + raw.unit() + "'");
        }

        if (point.dataType() == DataType.DOUBLE) {
            if (point.min() != null && numeric < point.min()) {
                throw new NormalizationException(
                        com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.OUT_OF_RANGE,
                        "point " + point.pointId() + " value " + numeric
                                + " below min " + point.min());
            }
            if (point.max() != null && numeric > point.max()) {
                throw new NormalizationException(
                        com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.OUT_OF_RANGE,
                        "point " + point.pointId() + " value " + numeric
                                + " above max " + point.max());
            }
        }

        Long ts = raw.timestamp();
        if (ts == null) {
            throw new NormalizationException(
                    com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.TIMESTAMP_MISSING,
                    "point " + point.pointId() + " timestamp is null");
        }
        if (lastAcceptedTimestamp >= 0 && ts < lastAcceptedTimestamp) {
            throw new NormalizationException(
                    com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.TIMESTAMP_OUT_OF_ORDER,
                    "point " + point.pointId() + " timestamp " + ts
                            + " is earlier than last accepted " + lastAcceptedTimestamp);
        }

        return new NormalizedRecord(0L, raw.deviceId(), point.pointId(),
                point.dataType(), point.unit(), numeric, flag, ts);
    }

    /** 兼容旧签名：无历史时间戳。 */
    public NormalizedRecord normalize(RawReading raw, Map<String, PointDefinition> points) {
        return normalize(raw, points, -1L);
    }

    private static Double toDouble(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        // 字符串数字不做隐式兼容："12.3" 与点位 DOUBLE 类型视为类型不匹配
        return null;
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }
}

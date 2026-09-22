package com.github.highcumontoa.edgedatagatewayjava.normalize;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizeResult;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;

import java.util.Map;
import java.util.Objects;

/**
 * 归一化器：把原始读数校验并转换为统一上行记录。
 *
 * <p>无状态、线程安全。校验按固定顺序进行，多重异常时返回排在最前的原因：
 * <ol>
 *   <li>{@link RejectReason#TIMESTAMP_MISSING} 时间戳缺失</li>
 *   <li>{@link RejectReason#UNKNOWN_POINT} 点位未登记</li>
 *   <li>{@link RejectReason#VALUE_NULL} 值为 null</li>
 *   <li>{@link RejectReason#UNIT_MISMATCH} 单位不匹配（点位定义了期望单位时）</li>
 *   <li>{@link RejectReason#TYPE_MISMATCH} 类型不匹配/非法数值（NaN、整型溢出）</li>
 *   <li>{@link RejectReason#OUT_OF_RANGE} 取值越界（含边界比较）</li>
 * </ol>
 * 时间戳乱序（{@link RejectReason#TIMESTAMP_OUT_OF_ORDER}）依赖每设备状态，
 * 由网关在接受后、入缓冲前判断，因此不会与上述原因同时出现。
 */
public final class Normalizer {

    /** 未分配序号：seq 由缓冲在 append 时统一分配。 */
    public static final long SEQ_UNASSIGNED = -1L;

    /** 点位目录的复合键：设备 + 点位。 */
    public static String catalogKey(String deviceId, String pointId) {
        return deviceId + "#" + pointId;
    }

    private final Map<String, PointDefinition> pointCatalog;

    public Normalizer(Map<String, PointDefinition> pointCatalog) {
        this.pointCatalog = Map.copyOf(pointCatalog);
    }

    /** 校验与转换（不分配 seq、不做乱序判断）。 */
    public NormalizeResult normalize(RawReading raw) {
        Objects.requireNonNull(raw, "raw");

        if (raw.timestamp() == null) {
            return reject(raw, RejectReason.TIMESTAMP_MISSING, "timestamp is null");
        }

        PointDefinition point = pointCatalog.get(catalogKey(raw.deviceId(), raw.pointId()));
        if (point == null) {
            return reject(raw, RejectReason.UNKNOWN_POINT,
                    "point not registered: " + raw.pointId());
        }

        if (raw.rawValue() == null) {
            return reject(raw, RejectReason.VALUE_NULL, "value is null");
        }

        String expectedUnit = point.unit();
        if (expectedUnit != null && !expectedUnit.isBlank()
                && !expectedUnit.equals(raw.rawUnit())) {
            return reject(raw, RejectReason.UNIT_MISMATCH,
                    "expected unit '" + expectedUnit + "' but got '" + raw.rawUnit() + "'");
        }

        Object value;
        try {
            value = convert(point, raw.rawValue());
        } catch (TypeMismatchException e) {
            return reject(raw, RejectReason.TYPE_MISMATCH, e.getMessage());
        }

        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (point.minInclusive() != null && d < point.minInclusive()) {
                return reject(raw, RejectReason.OUT_OF_RANGE,
                        "value " + d + " < min " + point.minInclusive());
            }
            if (point.maxInclusive() != null && d > point.maxInclusive()) {
                return reject(raw, RejectReason.OUT_OF_RANGE,
                        "value " + d + " > max " + point.maxInclusive());
            }
        }

        NormalizedRecord record = new NormalizedRecord(
                SEQ_UNASSIGNED,
                raw.deviceId(),
                point.pointId(),
                point.dataType(),
                value,
                expectedUnit == null ? "" : expectedUnit,
                raw.timestamp());
        return NormalizeResult.accept(record);
    }

    private static Object convert(PointDefinition point, Object raw) {
        DataType type = point.dataType();
        if (type == DataType.INT) {
            if (!(raw instanceof Number n)) {
                throw new TypeMismatchException(
                        "INT requires a number but got " + raw.getClass().getSimpleName());
            }
            double d = n.doubleValue();
            if (Double.isNaN(d)) {
                throw new TypeMismatchException("INT value is NaN");
            }
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw new TypeMismatchException(
                        "not an integral value in int range: " + raw);
            }
            return Integer.valueOf((int) d);
        }
        if (type == DataType.DOUBLE) {
            if (!(raw instanceof Number n)) {
                throw new TypeMismatchException(
                        "DOUBLE requires a number but got " + raw.getClass().getSimpleName());
            }
            double d = n.doubleValue();
            if (Double.isNaN(d)) {
                throw new TypeMismatchException("DOUBLE value is NaN");
            }
            return Double.valueOf(d);
        }
        if (type == DataType.BOOLEAN) {
            if (!(raw instanceof Boolean b)) {
                throw new TypeMismatchException(
                        "BOOLEAN requires a boolean but got " + raw.getClass().getSimpleName());
            }
            return b;
        }
        throw new TypeMismatchException("unsupported data type: " + type);
    }

    private static NormalizeResult reject(RawReading raw, RejectReason reason, String detail) {
        return NormalizeResult.reject(new RejectedReading(raw, reason, detail));
    }

    private static final class TypeMismatchException extends RuntimeException {
        TypeMismatchException(String message) {
            super(message);
        }
    }
}

package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizeResult;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.normalize.Normalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 畸形读数必须被拒绝且原因可区分；合法读数必须归一化为统一记录。 */
class NormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(NormalizerTest.class);

    private static final PointDefinition TEMP =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
    private static final PointDefinition RPM =
            new PointDefinition("rpm", DataType.INT, "rpm", 0.0, 10000.0);
    private static final PointDefinition RUNNING =
            new PointDefinition("running", DataType.BOOLEAN, "", null, null);

    private final Normalizer normalizer = new Normalizer(Map.of(
            Normalizer.catalogKey("d1", "temp"), TEMP,
            Normalizer.catalogKey("d1", "rpm"), RPM,
            Normalizer.catalogKey("d1", "running"), RUNNING));

    @Test
    void acceptsValidDoubleIntBoolean() {
        checkAccept(raw("d1", "temp", 21.5, "C", 1000L), DataType.DOUBLE, 21.5);
        checkAccept(raw("d1", "rpm", 3200, "rpm", 1001L), DataType.INT, 3200);
        checkAccept(raw("d1", "running", true, "", 1002L), DataType.BOOLEAN, true);
    }

    @Test
    void rejectsUnknownPoint() {
        checkReject(raw("d1", "ghost", 1, "C", 1000L), RejectReason.UNKNOWN_POINT);
    }

    @Test
    void rejectsNullValue() {
        checkReject(raw("d1", "temp", null, "C", 1000L), RejectReason.VALUE_NULL);
    }

    @Test
    void rejectsTypeMismatch() {
        checkReject(raw("d1", "temp", "21.5", "C", 1000L), RejectReason.TYPE_MISMATCH);
        checkReject(raw("d1", "running", "true", "", 1000L), RejectReason.TYPE_MISMATCH);
        // 小数不能静默截断成 int
        checkReject(raw("d1", "rpm", 3.5, "rpm", 1000L), RejectReason.TYPE_MISMATCH);
        // 越出 int 范围
        checkReject(raw("d1", "rpm", 1L << 40, "rpm", 1000L), RejectReason.TYPE_MISMATCH);
        // NaN 非法
        checkReject(raw("d1", "temp", Double.NaN, "C", 1000L), RejectReason.TYPE_MISMATCH);
    }

    @Test
    void rejectsOutOfRangeInclusiveBounds() {
        checkAccept(raw("d1", "temp", -40.0, "C", 1000L), DataType.DOUBLE, -40.0);
        checkAccept(raw("d1", "temp", 85.0, "C", 1000L), DataType.DOUBLE, 85.0);
        checkReject(raw("d1", "temp", -40.01, "C", 1000L), RejectReason.OUT_OF_RANGE);
        checkReject(raw("d1", "temp", 85.01, "C", 1000L), RejectReason.OUT_OF_RANGE);
        checkReject(raw("d1", "rpm", -1, "rpm", 1000L), RejectReason.OUT_OF_RANGE);
    }

    @Test
    void rejectsUnitMismatch() {
        checkReject(raw("d1", "temp", 21.5, "F", 1000L), RejectReason.UNIT_MISMATCH);
        checkReject(raw("d1", "temp", 21.5, null, 1000L), RejectReason.UNIT_MISMATCH);
        // 量纲无关点位不校验单位
        checkAccept(raw("d1", "running", false, null, 1000L), DataType.BOOLEAN, false);
    }

    @Test
    void rejectsMissingTimestamp() {
        checkReject(raw("d1", "temp", 21.5, "C", null), RejectReason.TIMESTAMP_MISSING);
    }

    @Test
    void checkOrderIsDeterministicTimestampFirst() {
        // 时间戳缺失优先于其它所有问题
        checkReject(raw("d1", "ghost", null, null, null), RejectReason.TIMESTAMP_MISSING);
        // 值 null 优先于单位/类型
        checkReject(raw("d1", "temp", null, "F", 1000L), RejectReason.VALUE_NULL);
    }

    private void checkAccept(RawReading r, DataType expectedType, Object expectedValue) {
        NormalizeResult result = normalizer.normalize(r);
        log.info("[原始读数] {}", r);
        assertTrue(result.accepted(), "should accept: " + r);
        assertEquals(expectedType, result.record().type());
        assertEquals(expectedValue, result.record().value());
        assertEquals("d1", result.record().deviceId());
        log.info("[归一化结果] {}", result.record());
    }

    private void checkReject(RawReading r, RejectReason expected) {
        NormalizeResult result = normalizer.normalize(r);
        log.info("[原始读数] {}", r);
        assertFalse(result.accepted(), "should reject: " + r);
        assertEquals(expected, result.rejected().reason(),
                "reason mismatch, detail=" + result.rejected().detail());
        log.info("[归一化结果] REJECTED reason={} detail={}",
                result.rejected().reason(), result.rejected().detail());
    }

    private static RawReading raw(String device, String point, Object value,
                                  String unit, Long ts) {
        return new RawReading(device, point, value, unit, ts);
    }
}

package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.PointDefinition;
import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.normalize.NormalizationException;
import com.github.highcumontoa.edgedatagatewayjava.normalize.ReadingNormalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖七类可区分拒绝原因与正常归一化路径。 */
class NormalizerTest {

    private static final Logger log = LoggerFactory.getLogger(NormalizerTest.class);

    private final ReadingNormalizer normalizer = new ReadingNormalizer();

    private final PointDefinition temp =
            new PointDefinition("temp", DataType.DOUBLE, "C", -40.0, 85.0);
    private final PointDefinition run =
            new PointDefinition("run", DataType.BOOLEAN, "bool", null, null);
    private final Map<String, PointDefinition> points = Map.of("temp", temp, "run", run);

    private RawReading r(String point, Object value, String unit, Long ts) {
        return new RawReading("dev", point, value, unit, ts);
    }

    private RejectReason reject(RawReading raw, long lastTs) {
        NormalizationException e = assertThrows(NormalizationException.class,
                () -> normalizer.normalize(raw, points, lastTs));
        log.warn("[NORMALIZER-REJECT] raw={} reason={} detail={}",
                raw, e.reason(), e.getMessage());
        return e.reason();
    }

    @Test
    void acceptsValidDoubleAndBoolean() {
        var nrD = normalizer.normalize(r("temp", 21.5, "C", 1000L), points, -1);
        log.info("[NORMALIZER-OK] raw=21.5 normalized={}", nrD);
        assertEquals(DataType.DOUBLE, nrD.dataType());
        assertEquals(21.5, nrD.numeric());
        assertEquals("C", nrD.unit());

        var nrB = normalizer.normalize(r("run", Boolean.TRUE, "bool", 1000L), points, -1);
        log.info("[NORMALIZER-OK] raw=true normalized={}", nrB);
        assertEquals(Boolean.TRUE, nrB.flag());
        assertNull(nrB.numeric());
    }

    @Test
    void distinguishesAllRejectReasons() {
        long ts = 5000L;
        assertEquals(RejectReason.UNKNOWN_POINT, reject(r("nope", 1.0, "C", ts), -1));
        assertEquals(RejectReason.VALUE_MISSING, reject(r("temp", null, "C", ts), -1));
        assertEquals(RejectReason.TYPE_MISMATCH, reject(r("temp", "21.5", "C", ts), -1));
        assertEquals(RejectReason.TYPE_MISMATCH, reject(r("run", "true", "bool", ts), -1));
        assertEquals(RejectReason.UNIT_MISMATCH, reject(r("temp", 21.5, "F", ts), -1));
        assertEquals(RejectReason.UNIT_MISMATCH, reject(r("temp", 21.5, null, ts), -1));
        assertEquals(RejectReason.OUT_OF_RANGE, reject(r("temp", 86.0, "C", ts), -1));
        assertEquals(RejectReason.OUT_OF_RANGE, reject(r("temp", -41.0, "C", ts), -1));
        assertEquals(RejectReason.TIMESTAMP_MISSING, reject(r("temp", 21.5, "C", null), -1));
        assertEquals(RejectReason.TIMESTAMP_OUT_OF_ORDER,
                reject(r("temp", 21.5, "C", 4999L), 5000L));
    }

    @Test
    void equalTimestampAcrossPointsIsAllowedButStrictRegressionRejected() {
        // 同一扫描时刻不同点位时间戳相等：允许
        normalizer.normalize(r("temp", 21.0, "C", 1000L), points, -1);
        normalizer.normalize(r("run", Boolean.TRUE, "bool", 1000L), points, 1000L);
        // 同一点位严格回退：拒绝
        NormalizationException e = assertThrows(NormalizationException.class,
                () -> normalizer.normalize(r("temp", 20.0, "C", 999L), points, 1000L));
        assertEquals(RejectReason.TIMESTAMP_OUT_OF_ORDER, e.reason());
        assertTrue(e.getMessage().contains("earlier"));
    }
}

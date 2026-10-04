package com.github.highcumontoa.edgedatagatewayjava.tests;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;
import com.github.highcumontoa.edgedatagatewayjava.stats.Reconciler;
import com.github.highcumontoa.edgedatagatewayjava.stats.ReconciliationIssue;
import com.github.highcumontoa.edgedatagatewayjava.stats.ReconciliationReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统计核对口径：自洽场景全绿；注入不一致时精确指出设备与指标类别。 */
class ReconcilerTest {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerTest.class);

    private Map<RejectReason, Long> rejects(long outOfRange, long outOfOrder) {
        Map<RejectReason, Long> m = new LinkedHashMap<>();
        for (RejectReason r : RejectReason.values()) {
            m.put(r, 0L);
        }
        m.put(RejectReason.OUT_OF_RANGE, outOfRange);
        m.put(RejectReason.TIMESTAMP_OUT_OF_ORDER, outOfOrder);
        return m;
    }

    private Reconciler.DeviceData device(String id, long polled, long failed,
                                         long accepted, long shed, long dropped,
                                         long delivered, long pending,
                                         Map<RejectReason, Long> rejected) {
        long rejectedTotal = rejected.values().stream().mapToLong(Long::longValue).sum();
        return new Reconciler.DeviceData(true, DeviceHealth.HEALTHY,
                polled, failed, accepted + shed + rejectedTotal,
                accepted, shed, dropped, delivered, pending, rejected);
    }

    @Test
    void consistentSnapshotProducesNoIssues() {
        // 设备 A：轮询 10 次全成功；接受 8，拒绝(越界1+乱序1)=2；已送达 6，DROP_OLDEST 1，pending 1
        var a = device("dev-A", 10, 0, 8, 0, 1, 6, 1, rejects(1, 1));
        // 设备 B：轮询 4 次（失败 1）；接受 5，REJECT_NEWEST 缺口 2；已送达 4，pending 1
        var b = device("dev-B", 4, 1, 5, 2, 0, 4, 1, rejects(0, 0));

        Map<String, Reconciler.DeviceData> devices = new LinkedHashMap<>();
        devices.put("dev-A", a);
        devices.put("dev-B", b);

        // accepted=13, shed=2 → nextSeq=15；delivered=10, droppedOldest=1, shed=2, pending=2
        var global = new Reconciler.GlobalData(14, 13, 2, 10, 1, 2, 2);
        ReconciliationReport report = Reconciler.reconcile(1L, 15L, 12L, global, devices);

        assertTrue(report.consistent(), () -> "unexpected issues: " + report.issues());
        assertTrue(report.issues().isEmpty());
        assertTrue(report.devices().get("dev-A").consistent());
        log.info("[统计核对] 自洽快照: accepted={} delivered={} droppedOldest={} shed={} pending={} → {}",
                report.globalAccepted(), report.globalDelivered(),
                report.globalDroppedOldest(), report.globalShedNewest(),
                report.globalPending(), report.consistent());
    }

    @Test
    void inconsistentDeviceCounterPinpointsDeviceAndMetric() {
        // dev-A 的 accepted 被人为少计 1：accepted=7 但 delivered+dropped+pending=8
        var a = device("dev-A", 10, 0, 7, 0, 1, 6, 1, rejects(1, 1));
        // 注意 received 也会因此对不上（accepted+shed+rejected=9 而 received=10）
        Map<String, Reconciler.DeviceData> devices = new LinkedHashMap<>();
        devices.put("dev-A", a);

        var global = new Reconciler.GlobalData(10, 7, 2, 6, 1, 0, 1);
        ReconciliationReport report = Reconciler.reconcile(1L, 7L, 0L, global, devices);

        assertFalse(report.consistent());
        ReconciliationIssue lifecycle = report.issues().stream()
                .filter(i -> "dev-A".equals(i.scope())
                        && Reconciler.M_ACCEPT_LIFECYCLE.equals(i.metric()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "expected pinpointed ACCEPT-LIFECYCLE issue, got " + report.issues()));
        assertEquals(7L, lifecycle.expected());
        assertEquals(8L, lifecycle.actual());
        log.info("[统计核对] 精确差异: scope={} metric={} expected={} actual={} detail={}",
                lifecycle.scope(), lifecycle.metric(), lifecycle.expected(),
                lifecycle.actual(), lifecycle.detail());
    }

    @Test
    void globalMismatchPinpointsGlobalMetric() {
        var a = device("dev-A", 10, 0, 8, 0, 1, 6, 1, rejects(1, 1));
        Map<String, Reconciler.DeviceData> devices = new LinkedHashMap<>();
        devices.put("dev-A", a);
        // 全局 accepted 错记为 9（设备之和为 8）
        var global = new Reconciler.GlobalData(10, 9, 2, 6, 1, 0, 1);
        ReconciliationReport report = Reconciler.reconcile(1L, 9L, 0L, global, devices);

        assertFalse(report.consistent());
        ReconciliationIssue gap = report.issues().stream()
                .filter(i -> ReconciliationIssue.SCOPE_GLOBAL.equals(i.scope())
                        && Reconciler.M_GLOBAL_DEV_ACCEPT.equals(i.metric()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "expected G-DEV-ACCEPTED issue, got " + report.issues()));
        assertEquals(9L, gap.expected());
        assertEquals(8L, gap.actual());
        log.info("[统计核对] 全局差异: {} expected={} actual={}",
                gap.metric(), gap.expected(), gap.actual());
    }

    @Test
    void seqLifecycleIdentityHoldsAcrossOverflowAndPending() {
        // 15 个已分配 seq：送达 10、DROP_OLDEST 2、REJECT_NEWEST 缺口 1、pending 2
        var a = device("dev-A", 20, 0, 12, 1, 2, 10, 2, rejects(0, 0));
        Map<String, Reconciler.DeviceData> devices = new LinkedHashMap<>();
        devices.put("dev-A", a);
        // 全局：accepted=12, shed=1 → nextSeq=13... 构造 accepted=14,shed=1 → nextSeq=15
        var a2 = device("dev-A", 20, 0, 14, 1, 2, 10, 2, rejects(0, 0));
        devices.put("dev-A", a2);
        var global = new Reconciler.GlobalData(20, 14, 0, 10, 2, 1, 2);
        ReconciliationReport report = Reconciler.reconcile(1L, 15L, 10L, global, devices);
        assertTrue(report.consistent(), () -> report.issues().toString());
        log.info("[统计核对] seq 守恒: nextSeq={} = delivered {} + droppedOldest {} + shed {} + pending {}",
                report.nextSeq(), report.globalDelivered(), report.globalDroppedOldest(),
                report.globalShedNewest(), report.globalPending());
    }
}

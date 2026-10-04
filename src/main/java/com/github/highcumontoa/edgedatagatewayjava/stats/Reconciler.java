package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计一致性核对器（纯函数、无状态）。
 *
 * <p>调用方必须在同一把一致性读锁内采集全部输入（缓冲位点 + pending 分组 +
 * 全部设备计数），保证生成的报告对同一状态快照自洽，不会在并发采集/补传下
 * 拼出"自相矛盾的一版"。
 *
 * <p>发现不一致时，{@link ReconciliationIssue} 精确指出设备（或 GLOBAL）、
 * 指标类别、期望值与实际值，而非笼统失败。
 */
public final class Reconciler {

    // ---- 指标类别标识（确定性字符串，便于测试与日志定位）----
    public static final String M_POLL = "POLL";
    public static final String M_RECEIVED = "RECEIVED";
    public static final String M_REJECT = "REJECT";
    public static final String M_ACCEPT_LIFECYCLE = "ACCEPT-LIFECYCLE";
    public static final String M_GLOBAL_DEV_POLL = "G-DEV-POLLED";
    public static final String M_GLOBAL_DEV_ACCEPT = "G-DEV-ACCEPTED";
    public static final String M_GLOBAL_DEV_REJECT = "G-DEV-REJECTED";
    public static final String M_GLOBAL_DEV_DELIVERED = "G-DEV-DELIVERED";
    public static final String M_GLOBAL_DEV_DROPPED = "G-DEV-DROPPED";
    public static final String M_GLOBAL_SEQ = "G-SEQ-ALLOCATED";
    public static final String M_GLOBAL_LIFECYCLE = "G-SEQ-LIFECYCLE";

    private Reconciler() {
    }

    /** 全局计数输入。 */
    public record GlobalData(
            long polled,
            long accepted,
            long rejected,
            long delivered,
            long droppedOldest,
            long shedNewest,
            int pending
    ) {
    }

    /** 单设备计数输入（拒绝按原因分项）。 */
    public record DeviceData(
            boolean online,
            DeviceHealth health,
            long polled,
            long failedPolls,
            long received,
            long accepted,
            long shedNewest,
            long droppedOldest,
            long delivered,
            long pending,
            Map<RejectReason, Long> rejectedByReason
    ) {
    }

    /** 生成核对结论。 */
    public static ReconciliationReport reconcile(long nowMillis,
                                                 long nextSeq,
                                                 long confirmedSeq,
                                                 GlobalData global,
                                                 Map<String, DeviceData> devices) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        Map<String, DeviceReconciliation> perDevice = new LinkedHashMap<>();

        long sumPolled = 0L;
        long sumAccepted = 0L;
        long sumRejected = 0L;
        long sumDelivered = 0L;
        long sumDroppedOldest = 0L;
        long sumShedNewest = 0L;
        long sumPending = 0L;

        for (Map.Entry<String, DeviceData> e : devices.entrySet()) {
            String id = e.getKey();
            DeviceData d = e.getValue();
            long rejectedTotal = d.rejectedByReason().values().stream()
                    .mapToLong(Long::longValue).sum();
            List<ReconciliationIssue> deviceIssues = reconcileDevice(id, d);
            perDevice.put(id, new DeviceReconciliation(
                    id, d.online(), d.health(),
                    d.polled(), d.polled() - d.failedPolls(), d.failedPolls(),
                    rejectedTotal, d.received(), d.accepted(), d.shedNewest(),
                    d.droppedOldest(), d.delivered(), d.pending(),
                    deviceIssues.isEmpty(), List.copyOf(deviceIssues)));
            issues.addAll(deviceIssues);

            sumPolled += d.polled();
            sumAccepted += d.accepted();
            sumRejected += rejectedTotal;
            sumDelivered += d.delivered();
            sumDroppedOldest += d.droppedOldest();
            sumShedNewest += d.shedNewest();
            sumPending += d.pending();
        }

        // ---- 全局：逐设备之和 == 全局值 ----
        checkGlobal(issues, M_GLOBAL_DEV_POLL, global.polled(), sumPolled,
                "sum(device.polled) must equal global polled");
        checkGlobal(issues, M_GLOBAL_DEV_ACCEPT, global.accepted(), sumAccepted,
                "sum(device.accepted) must equal global accepted");
        checkGlobal(issues, M_GLOBAL_DEV_REJECT, global.rejected(), sumRejected,
                "sum(device.rejected) must equal global rejected");
        checkGlobal(issues, M_GLOBAL_DEV_DELIVERED, global.delivered(), sumDelivered,
                "sum(device.delivered) must equal global delivered");
        long globalDropped = global.droppedOldest() + global.shedNewest();
        checkGlobal(issues, M_GLOBAL_DEV_DROPPED, globalDropped,
                sumDroppedOldest + sumShedNewest,
                "sum(device.droppedOldest+shedNewest) must equal global dropped");
        checkGlobal(issues, M_GLOBAL_DEV_DROPPED + "-PENDING", global.pending(), sumPending,
                "sum(device.pending) must equal buffer pending count");

        // ---- 全局：seq 分配口径 ----
        // 每个被分配的 seq 恰好来自一条"被接受"记录；REJECT_NEWEST 同样消耗 seq。
        long allocated = nextSeq;
        long expectAllocated = global.accepted() + global.shedNewest();
        checkGlobal(issues, M_GLOBAL_SEQ, expectAllocated, allocated,
                "allocated seq count (nextSeq) must equal accepted + shedNewest(REJECT_NEWEST)");

        // ---- 全局：已分配 seq 的去向守恒 ----
        // 已分配 seq = 下游送达 + DROP_OLDEST 丢弃 + REJECT_NEWEST 缺口 + 当前 pending
        long lifecycle = global.delivered() + global.droppedOldest()
                + global.shedNewest() + global.pending();
        checkGlobal(issues, M_GLOBAL_LIFECYCLE, allocated, lifecycle,
                "allocated seq = delivered + droppedOldest + shedNewest + pending");

        return new ReconciliationReport(nowMillis, issues.isEmpty(), nextSeq, confirmedSeq,
                global.polled(), global.accepted(), global.rejected(), global.delivered(),
                global.droppedOldest(), global.shedNewest(), global.pending(),
                List.copyOf(issues), perDevice);
    }

    private static List<ReconciliationIssue> reconcileDevice(String id, DeviceData d) {
        List<ReconciliationIssue> issues = new ArrayList<>();
        long successful = d.polled() - d.failedPolls();
        if (successful < 0L) {
            issues.add(ReconciliationIssue.device(id, M_POLL, d.failedPolls(), d.polled(),
                    "failedPolls (" + d.failedPolls() + ") exceeds polled (" + d.polled()
                            + "); expected successful=polled-failed >= 0"));
        }

        long rejectedTotal = d.rejectedByReason().values().stream()
                .mapToLong(Long::longValue).sum();
        long expectReceived = d.accepted() + d.shedNewest() + rejectedTotal;
        if (expectReceived != d.received()) {
            issues.add(ReconciliationIssue.device(id, M_RECEIVED, d.received(), expectReceived,
                    "received (" + d.received() + ") != accepted(" + d.accepted()
                            + ") + shedNewest(" + d.shedNewest() + ") + rejected("
                            + rejectedTotal + ")"));
        }

        // 各原因分项非负（EnumMap 已保证求和口径，此处防御性保留显式分项可见）
        for (Map.Entry<RejectReason, Long> en : d.rejectedByReason().entrySet()) {
            if (en.getValue() < 0L) {
                issues.add(ReconciliationIssue.device(id,
                        M_REJECT + ":" + en.getKey().name(), 0L, en.getValue(),
                        "rejected count for reason must be non-negative"));
            }
        }

        long expectAccepted = d.delivered() + d.droppedOldest() + d.pending();
        if (expectAccepted != d.accepted()) {
            issues.add(ReconciliationIssue.device(id, M_ACCEPT_LIFECYCLE,
                    d.accepted(), expectAccepted,
                    "accepted (" + d.accepted() + ") != delivered(" + d.delivered()
                            + ") + droppedOldest(" + d.droppedOldest() + ") + pending("
                            + d.pending() + ")"));
        }
        return issues;
    }

    private static void checkGlobal(List<ReconciliationIssue> issues, String metric,
                                    long expected, long actual, String detail) {
        if (expected != actual) {
            issues.add(ReconciliationIssue.global(metric, expected, actual,
                    detail + " [expected=" + expected + ", actual=" + actual + "]"));
        }
    }
}

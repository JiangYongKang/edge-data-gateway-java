package com.github.highcumontoa.edgedatagatewayjava.stats;

/**
 * 一条具体的统计核对差异：精确指出设备（或全局）、指标类别与期望值/实际值。
 *
 * @param scope    GLOBAL 或 deviceId（逐设备问题时）
 * @param metric   不一致的指标类别（确定性标识）
 * @param expected 期望值
 * @param actual   实际值
 * @param detail   人类可读说明
 */
public record ReconciliationIssue(
        String scope,
        String metric,
        long expected,
        long actual,
        String detail
) {
    public static final String SCOPE_GLOBAL = "GLOBAL";

    public static ReconciliationIssue global(String metric, long expected, long actual,
                                             String detail) {
        return new ReconciliationIssue(SCOPE_GLOBAL, metric, expected, actual, detail);
    }

    public static ReconciliationIssue device(String deviceId, String metric,
                                             long expected, long actual, String detail) {
        return new ReconciliationIssue(deviceId, metric, expected, actual, detail);
    }
}

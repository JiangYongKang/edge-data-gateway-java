package com.github.highcumontoa.edgedatagatewayjava.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 网关配置项（前缀 edge.gateway）。骨架阶段仅声明字段默认值。 */
@ConfigurationProperties(prefix = "edge.gateway")
public class GatewayProperties {

    /** 持久化目录（spool / 位点 / 拒绝日志）。 */
    private String dataDir = "data/gateway";
    /** 缓冲容量上限（未确认记录条数）。 */
    private int spoolCapacity = 1000;
    /** 每设备每轮采集最大条数。 */
    private int pollBatchSize = 10;
    /** 采集周期间隔（毫秒）。 */
    private long collectIntervalMs = 200;
    /** 补传周期间隔（毫秒）。 */
    private long replayIntervalMs = 200;
    /** 启动时是否自动运行（数据为空时便于观察）。 */
    private boolean autoStart = true;
    /** 是否装配内置演示设备脚本。 */
    private boolean demoDevices = true;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getSpoolCapacity() { return spoolCapacity; }
    public void setSpoolCapacity(int spoolCapacity) { this.spoolCapacity = spoolCapacity; }
    public int getPollBatchSize() { return pollBatchSize; }
    public void setPollBatchSize(int pollBatchSize) { this.pollBatchSize = pollBatchSize; }
    public long getCollectIntervalMs() { return collectIntervalMs; }
    public void setCollectIntervalMs(long collectIntervalMs) { this.collectIntervalMs = collectIntervalMs; }
    public long getReplayIntervalMs() { return replayIntervalMs; }
    public void setReplayIntervalMs(long replayIntervalMs) { this.replayIntervalMs = replayIntervalMs; }
    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
    public boolean isDemoDevices() { return demoDevices; }
    public void setDemoDevices(boolean demoDevices) { this.demoDevices = demoDevices; }
}

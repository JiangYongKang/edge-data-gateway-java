package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthState;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备级状态（统计基线 + 健康状态）的 JSON 持久化。
 *
 * <p>与缓冲 WAL/位点分离存放：位点保证数据不丢不重，本文件保证
 * 统计与健康判定在重启后续算而不是清零重算。写盘采用“临时文件 + 原子 rename”，
 * 崩溃时要么旧版要么新版，不会出现半写文件。
 *
 * <p>兼容范围：文件缺失或字段缺失时按零基线/HEALTHY 处理（即旧版本数据目录
 * 可直接升级）；未知字段忽略。
 */
public final class DeviceStateStore {

    public record PersistedState(Map<String, DeviceCounters> counters,
                                 Map<String, DeviceHealth> health) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path tmp;

    public DeviceStateStore(Path file) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
    }

    public PersistedState load() throws IOException {
        if (!Files.exists(file)) {
            return new PersistedState(Map.of(), Map.of());
        }
        Dto dto = MAPPER.readValue(file.toFile(), Dto.class);
        Map<String, DeviceCounters> counters = new LinkedHashMap<>();
        Map<String, DeviceHealth> health = new LinkedHashMap<>();
        if (dto.devices != null) {
            for (Map.Entry<String, DeviceDto> e : dto.devices.entrySet()) {
                String id = e.getKey();
                DeviceDto d = e.getValue();
                Map<RejectReason, Long> rejected = new EnumMap<>(RejectReason.class);
                if (d.rejectedByReason != null) {
                    d.rejectedByReason.forEach((r, n) -> {
                        if (r != null && n != null) {
                            rejected.put(r, n);
                        }
                    });
                }
                counters.put(id, new DeviceCounters(id, d.polls, d.okPolls, d.pollFailures,
                        d.internalErrors, d.accepted, rejected, d.delivered,
                        d.droppedOldest, d.rejectedNewest));
                health.put(id, new DeviceHealth(id,
                        d.healthState == null ? HealthState.HEALTHY : d.healthState,
                        d.consecutiveFailures, d.consecutiveSuccesses,
                        d.lastError, d.lastErrorAt, d.lastTransitionAt,
                        d.lastTransitionReason));
            }
        }
        return new PersistedState(counters, health);
    }

    public void save(List<DeviceCounters> counters, List<DeviceHealth> health)
            throws IOException {
        Map<String, DeviceHealth> byId = new LinkedHashMap<>();
        for (DeviceHealth h : health) {
            byId.put(h.deviceId(), h);
        }
        Map<String, DeviceDto> devices = new LinkedHashMap<>();
        for (DeviceCounters c : counters) {
            DeviceHealth h = byId.getOrDefault(c.deviceId(), null);
            DeviceDto d = new DeviceDto();
            d.polls = c.polls();
            d.okPolls = c.okPolls();
            d.pollFailures = c.pollFailures();
            d.internalErrors = c.internalErrors();
            d.accepted = c.accepted();
            d.rejectedByReason = c.rejectedByReason().isEmpty()
                    ? new EnumMap<>(RejectReason.class)
                    : new EnumMap<>(c.rejectedByReason());
            d.delivered = c.delivered();
            d.droppedOldest = c.droppedOldest();
            d.rejectedNewest = c.rejectedNewest();
            if (h != null) {
                d.healthState = h.state();
                d.consecutiveFailures = h.consecutiveFailures();
                d.consecutiveSuccesses = h.consecutiveSuccesses();
                d.lastError = h.lastError();
                d.lastErrorAt = h.lastErrorAt();
                d.lastTransitionAt = h.lastTransitionAt();
                d.lastTransitionReason = h.lastTransitionReason();
            }
            devices.put(c.deviceId(), d);
        }
        // 健康表里有但统计表里没有的设备（尚未产生任何计数）也要落盘
        for (DeviceHealth h : health) {
            if (!devices.containsKey(h.deviceId())) {
                DeviceDto d = new DeviceDto();
                d.healthState = h.state();
                d.consecutiveFailures = h.consecutiveFailures();
                d.consecutiveSuccesses = h.consecutiveSuccesses();
                d.lastError = h.lastError();
                d.lastErrorAt = h.lastErrorAt();
                d.lastTransitionAt = h.lastTransitionAt();
                d.lastTransitionReason = h.lastTransitionReason();
                devices.put(h.deviceId(), d);
            }
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        MAPPER.writeValue(tmp.toFile(), new Dto(devices));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Jackson 传输对象（缺字段即零值，保证向后兼容）。 */
    public static final class Dto {
        @JsonProperty("devices") public Map<String, DeviceDto> devices;

        public Dto() {
        }

        @JsonCreator
        public Dto(@JsonProperty("devices") Map<String, DeviceDto> devices) {
            this.devices = devices;
        }
    }

    /** 单设备持久化字段。 */
    public static final class DeviceDto {
        @JsonProperty("polls") public long polls;
        @JsonProperty("okPolls") public long okPolls;
        @JsonProperty("pollFailures") public long pollFailures;
        @JsonProperty("internalErrors") public long internalErrors;
        @JsonProperty("accepted") public long accepted;
        @JsonProperty("rejectedByReason") public Map<RejectReason, Long> rejectedByReason;
        @JsonProperty("delivered") public long delivered;
        @JsonProperty("droppedOldest") public long droppedOldest;
        @JsonProperty("rejectedNewest") public long rejectedNewest;
        @JsonProperty("healthState") public HealthState healthState;
        @JsonProperty("consecutiveFailures") public long consecutiveFailures;
        @JsonProperty("consecutiveSuccesses") public long consecutiveSuccesses;
        @JsonProperty("lastError") public String lastError;
        @JsonProperty("lastErrorAt") public Long lastErrorAt;
        @JsonProperty("lastTransitionAt") public Long lastTransitionAt;
        @JsonProperty("lastTransitionReason") public String lastTransitionReason;
    }

    /** 保留 List 引用（API 签名使用），避免无用 import 告警。 */
    @SuppressWarnings("unused")
    private static final List<?> KEEP = new ArrayList<>();
}

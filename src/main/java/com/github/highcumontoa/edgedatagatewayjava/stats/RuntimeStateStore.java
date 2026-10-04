package com.github.highcumontoa.edgedatagatewayjava.stats;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.edgedatagatewayjava.health.DeviceHealth;
import com.github.highcumontoa.edgedatagatewayjava.health.HealthTransition;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备运行时状态（健康 + 连续成败 + 计数基线）持久化。
 *
 * <p>原子落盘：先写 {@code .tmp} 再原子 rename，崩溃时状态文件要么是旧版要么是新版。
 * 重启后 {@link DeviceRuntime#restore} 用加载到的值整体衔接，不清零重算。
 */
public interface RuntimeStateStore {

    /** 加载持久化状态；文件不存在时返回空映射（全部设备按初始态启动）。 */
    Map<String, DeviceRuntimeState> load() throws IOException;

    /** 原子保存全部设备状态。 */
    void save(Map<String, DeviceRuntimeState> states) throws IOException;

    /** 基于 JSON 文件的实现。 */
    final class JsonFile implements RuntimeStateStore {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Path file;
        private final Path tmp;

        public JsonFile(Path file) {
            this.file = file;
            this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        }

        @Override
        public Map<String, DeviceRuntimeState> load() throws IOException {
            if (!Files.exists(file)) {
                return Map.of();
            }
            FileDto dto = MAPPER.readValue(file.toFile(), FileDto.class);
            Map<String, DeviceRuntimeState> out = new LinkedHashMap<>();
            if (dto.devices != null) {
                for (Map.Entry<String, DeviceDto> e : dto.devices.entrySet()) {
                    out.put(e.getKey(), toState(e.getValue()));
                }
            }
            return out;
        }

        @Override
        public void save(Map<String, DeviceRuntimeState> states) throws IOException {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Map<String, DeviceDto> dto = new LinkedHashMap<>();
            states.forEach((id, s) -> dto.put(id, toDto(s)));
            MAPPER.writeValue(tmp.toFile(), new FileDto(dto));
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        private static DeviceDto toDto(DeviceRuntimeState s) {
            Map<String, Long> rejectedStrings = new LinkedHashMap<>();
            s.rejectedByReason().forEach((k, v) -> rejectedStrings.put(k.name(), v));
            return new DeviceDto(s.online(), s.health() == null ? null : s.health().name(),
                    s.consecutiveFailures(), s.consecutiveSuccesses(),
                    transitionDto(s.lastTransition()),
                    s.lastPollAt(), s.lastFailureAt(), s.lastSuccessAt(), s.lastError(),
                    rejectedStrings,
                    s.polled(), s.failedPolls(), s.received(), s.accepted(),
                    s.shedNewest(), s.droppedOldest(), s.delivered());
        }

        private static TransitionDto transitionDto(HealthTransition t) {
            return t == null ? null
                    : new TransitionDto(t.from() == null ? null : t.from().name(),
                            t.to().name(), t.reason(), t.atMillis());
        }

        private static DeviceRuntimeState toState(DeviceDto d) {
            DeviceHealth health = d.health == null ? DeviceHealth.HEALTHY
                    : DeviceHealth.valueOf(d.health);
            HealthTransition transition = null;
            if (d.lastTransition != null) {
                transition = new HealthTransition(
                        d.lastTransition.from == null ? null
                                : DeviceHealth.valueOf(d.lastTransition.from),
                        DeviceHealth.valueOf(d.lastTransition.to),
                        d.lastTransition.reason, d.lastTransition.atMillis);
            }
            Map<RejectReason, Long> rejected = new LinkedHashMap<>();
            if (d.rejectedByReason != null) {
                d.rejectedByReason.forEach((k, v) -> rejected.put(RejectReason.valueOf(k), v));
            }
            return new DeviceRuntimeState(
                    d.online, health, d.consecutiveFailures, d.consecutiveSuccesses,
                    transition, d.lastPollAt, d.lastFailureAt, d.lastSuccessAt, d.lastError,
                    rejected,
                    d.polled, d.failedPolls, d.received, d.accepted,
                    d.shedNewest, d.droppedOldest, d.delivered);
        }
    }

    /** 顶层文件传输对象。 */
    public static final class FileDto {
        @JsonProperty("devices")
        public Map<String, DeviceDto> devices;

        public FileDto() {
        }

        @JsonCreator
        public FileDto(@JsonProperty("devices") Map<String, DeviceDto> devices) {
            this.devices = devices;
        }
    }

    /** 单设备传输对象（枚举以字符串存储，跨版本可读）。 */
    public static final class DeviceDto {
        @JsonProperty("online") public boolean online;
        @JsonProperty("health") public String health;
        @JsonProperty("consecutiveFailures") public int consecutiveFailures;
        @JsonProperty("consecutiveSuccesses") public int consecutiveSuccesses;
        @JsonProperty("lastTransition") public TransitionDto lastTransition;
        @JsonProperty("lastPollAt") public long lastPollAt;
        @JsonProperty("lastFailureAt") public long lastFailureAt;
        @JsonProperty("lastSuccessAt") public long lastSuccessAt;
        @JsonProperty("lastError") public String lastError;
        @JsonProperty("rejectedByReason") public Map<String, Long> rejectedByReason;
        @JsonProperty("polled") public long polled;
        @JsonProperty("failedPolls") public long failedPolls;
        @JsonProperty("received") public long received;
        @JsonProperty("accepted") public long accepted;
        @JsonProperty("shedNewest") public long shedNewest;
        @JsonProperty("droppedOldest") public long droppedOldest;
        @JsonProperty("delivered") public long delivered;

        public DeviceDto() {
        }

        @JsonCreator
        public DeviceDto(@JsonProperty("online") boolean online,
                         @JsonProperty("health") String health,
                         @JsonProperty("consecutiveFailures") int consecutiveFailures,
                         @JsonProperty("consecutiveSuccesses") int consecutiveSuccesses,
                         @JsonProperty("lastTransition") TransitionDto lastTransition,
                         @JsonProperty("lastPollAt") long lastPollAt,
                         @JsonProperty("lastFailureAt") long lastFailureAt,
                         @JsonProperty("lastSuccessAt") long lastSuccessAt,
                         @JsonProperty("lastError") String lastError,
                         @JsonProperty("rejectedByReason") Map<String, Long> rejectedByReason,
                         @JsonProperty("polled") long polled,
                         @JsonProperty("failedPolls") long failedPolls,
                         @JsonProperty("received") long received,
                         @JsonProperty("accepted") long accepted,
                         @JsonProperty("shedNewest") long shedNewest,
                         @JsonProperty("droppedOldest") long droppedOldest,
                         @JsonProperty("delivered") long delivered) {
            this.online = online;
            this.health = health;
            this.consecutiveFailures = consecutiveFailures;
            this.consecutiveSuccesses = consecutiveSuccesses;
            this.lastTransition = lastTransition;
            this.lastPollAt = lastPollAt;
            this.lastFailureAt = lastFailureAt;
            this.lastSuccessAt = lastSuccessAt;
            this.lastError = lastError;
            this.rejectedByReason = rejectedByReason;
            this.polled = polled;
            this.failedPolls = failedPolls;
            this.received = received;
            this.accepted = accepted;
            this.shedNewest = shedNewest;
            this.droppedOldest = droppedOldest;
            this.delivered = delivered;
        }
    }

    /** 最近一次健康切换传输对象。 */
    public static final class TransitionDto {
        @JsonProperty("from") public String from;
        @JsonProperty("to") public String to;
        @JsonProperty("reason") public String reason;
        @JsonProperty("atMillis") public long atMillis;

        public TransitionDto() {
        }

        @JsonCreator
        public TransitionDto(@JsonProperty("from") String from,
                             @JsonProperty("to") String to,
                             @JsonProperty("reason") String reason,
                             @JsonProperty("atMillis") long atMillis) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.atMillis = atMillis;
        }
    }
}

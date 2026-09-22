package com.github.highcumontoa.edgedatagatewayjava.buffer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** 缓冲持久状态（seq 位点 + 每设备最后接受时间戳）。 */
public record BufferStateStore(long nextSeq, long confirmedSeq, Map<String, Long> lastTsByDevice) {

    public BufferStateStore {
        lastTsByDevice = Map.copyOf(lastTsByDevice);
    }

    public static BufferStateStore empty() {
        return new BufferStateStore(0L, -1L, Map.of());
    }

    /** 基于 JSON 文件的持久化：先写临时文件再原子 rename，崩溃时状态文件要么是旧版要么是新版。 */
    public static final class JsonFile {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Path file;
        private final Path tmp;

        public JsonFile(Path file) {
            this.file = file;
            this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        }

        public BufferStateStore load() throws IOException {
            if (!Files.exists(file)) {
                return BufferStateStore.empty();
            }
            Dto dto = MAPPER.readValue(file.toFile(), Dto.class);
            long next = Math.max(0L, dto.nextSeq);
            long confirmed = dto.confirmedSeq < next ? dto.confirmedSeq : next - 1;
            Map<String, Long> ts = dto.lastTsByDevice == null
                    ? Map.of() : new LinkedHashMap<>(dto.lastTsByDevice);
            return new BufferStateStore(next, confirmed, ts);
        }

        public void save(BufferStateStore state) throws IOException {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            MAPPER.writeValue(tmp.toFile(), new Dto(state.nextSeq(), state.confirmedSeq(),
                    state.lastTsByDevice()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        /** Jackson 传输对象。 */
        public static final class Dto {
            @JsonProperty("nextSeq") public long nextSeq;
            @JsonProperty("confirmedSeq") public long confirmedSeq;
            @JsonProperty("lastTsByDevice") public Map<String, Long> lastTsByDevice;

            public Dto() {
            }

            @JsonCreator
            public Dto(@JsonProperty("nextSeq") long nextSeq,
                       @JsonProperty("confirmedSeq") long confirmedSeq,
                       @JsonProperty("lastTsByDevice") Map<String, Long> lastTsByDevice) {
                this.nextSeq = nextSeq;
                this.confirmedSeq = confirmedSeq;
                this.lastTsByDevice = lastTsByDevice;
            }
        }
    }
}

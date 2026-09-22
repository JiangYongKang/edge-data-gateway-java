package com.github.highcumontoa.edgedatagatewayjava.spool;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备采集位点持久化。
 *
 * <p>文件 {@code checkpoints.dat}：每行 {@code deviceId\toffset\tlastAcceptedTimestamp}，
 * 全部位点保存在一个文件中；保存时以“临时文件 + 原子移动”整体重写，
 * 保证读到的永远是完整一致的位点快照，重启后不会从错误位点继续。
 *
 * <p>位点推进与数据入 spool 的顺序由编排层保证：
 * 先将归一化记录 append 到 spool（已落盘），再保存位点，
 * 因此崩溃最坏情况是“位点落后 → 重放同一读数”，由时间戳乱序规则/去重保护，
 * 不会丢已确认数据，也不会跳过未入缓冲的数据。
 */
public class DeviceCheckpointStore {

    /** deviceId -> [offset, lastAcceptedTimestamp] */
    private final Map<String, long[]> state = new HashMap<>();
    private Path file;

    public void init(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            this.file = dataDir.resolve("checkpoints.dat");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] f = line.split("\t", -1);
                    if (f.length != 3) {
                        throw new IllegalStateException("corrupt checkpoint line: " + line);
                    }
                    state.put(f[0], new long[]{Long.parseLong(f[1]), Long.parseLong(f[2])});
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to init checkpoints in " + dataDir, e);
        }
    }

    /** 读取已持久化的采集位点；不存在返回 0。 */
    public long loadOffset(String deviceId) {
        long[] s = state.get(deviceId);
        return s == null ? 0 : s[0];
    }

    /** 读取已持久化的最近已接受时间戳；不存在返回 -1。 */
    public long loadLastTimestamp(String deviceId) {
        long[] s = state.get(deviceId);
        return s == null ? -1 : s[1];
    }

    /** 原子保存位点与最近已接受时间戳（更新内存状态后整体重写文件）。 */
    public synchronized void save(String deviceId, long offset, long lastAcceptedTimestamp) {
        state.put(deviceId, new long[]{offset, lastAcceptedTimestamp});
        Path tmp = file.resolveSibling("checkpoints.dat.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Map.Entry<String, long[]> e : state.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1]);
                w.newLine();
            }
            w.flush();
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save checkpoint for " + deviceId, e);
        }
    }
}

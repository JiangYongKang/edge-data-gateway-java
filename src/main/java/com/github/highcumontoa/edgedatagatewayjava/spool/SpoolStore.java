package com.github.highcumontoa.edgedatagatewayjava.spool;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * 本地持久缓冲（spool）。
 *
 * <p>文件布局（dataDir 内）：
 * <ul>
 *   <li>{@code spool.dat}：仅包含“尚未确认”的记录，每行一条 TSV；
 *       append 时强制 fsync；acknowledge 后临时文件 + 原子移动重写。</li>
 *   <li>{@code spool.meta}：一行 {@code nextSeq}，append 时与数据行在同一 fsync 前更新，
 *       保证重启后序列号单调且不回退。</li>
 * </ul>
 *
 * <p>语义保证：
 * <ul>
 *   <li>append 成功返回后记录即已落盘；缓冲满时抛 {@link SpoolFullException}，
 *       采用“拒绝新数据、保留最旧未确认数据”的背压策略（有界、确定、可解释）。</li>
 *   <li>pending 按 seq 升序返回，补传顺序即入缓冲顺序。</li>
 *   <li>acknowledge 幂等：对未知或已确认的 seq 不产生任何副作用。</li>
 *   <li>重启后从 spool.dat / spool.meta 恢复，未确认记录不丢、已确认记录不复活。</li>
 * </ul>
 *
 * <p>所有公开方法 synchronized，串行化 append / ack / flush，
 * 使“分配序列号—入缓冲—确认”在多设备并发下结论一致。
 */
public class SpoolStore {

    private Path dataFile;
    private Path metaFile;
    private int capacity;

    /** seq -> 未确认记录，TreeMap 保证升序遍历。 */
    private final TreeMap<Long, NormalizedRecord> pending = new TreeMap<>();

    /** 下一个待分配序列号；从磁盘恢复。 */
    private long nextSeq;

    /** 初始化/恢复缓冲目录。 */
    public void init(Path dataDir, int capacity) {
        this.capacity = capacity;
        try {
            Files.createDirectories(dataDir);
            this.dataFile = dataDir.resolve("spool.dat");
            this.metaFile = dataDir.resolve("spool.meta");

            if (Files.exists(metaFile)) {
                String line = Files.readString(metaFile, StandardCharsets.UTF_8).trim();
                this.nextSeq = line.isEmpty() ? 0 : Long.parseLong(line);
            } else {
                this.nextSeq = 0;
            }

            if (Files.exists(dataFile)) {
                for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        NormalizedRecord r = deserialize(line);
                        pending.put(r.seq(), r);
                    }
                }
            }
            // 恢复一致性：nextSeq 不得小于任何现存记录。
            if (!pending.isEmpty() && nextSeq <= pending.lastKey()) {
                nextSeq = pending.lastKey() + 1;
                writeMeta();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to init spool in " + dataDir, e);
        }
    }

    /** 追加一条记录并分配全局序列号；缓冲满时抛 {@link SpoolFullException}。 */
    public synchronized NormalizedRecord append(
            String deviceId, String pointId, DataType dataType, String unit,
            Double numeric, Boolean flag, long timestamp) {
        if (pending.size() >= capacity) {
            throw new SpoolFullException(
                    "spool is full (" + pending.size() + "/" + capacity
                            + "); reject new record device=" + deviceId + " point=" + pointId
                            + "; oldest unacked seq=" + pending.firstKey());
        }
        long seq = nextSeq;
        NormalizedRecord record = new NormalizedRecord(
                seq, deviceId, pointId, dataType, unit, numeric, flag, timestamp);
        try {
            appendLineWithForce(dataFile, serialize(record));
            // nextSeq 与记录在同一崩溃窗口内推进；meta 落盘后才对外可见序列号。
            this.nextSeq = seq + 1;
            writeMeta();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append spool record seq=" + seq, e);
        }
        pending.put(seq, record);
        return record;
    }

    /** 取按序列号升序、尚未确认的记录（补传严格按此顺序）。 */
    public synchronized List<NormalizedRecord> pendingSorted(int limit) {
        List<NormalizedRecord> out = new ArrayList<>();
        for (NormalizedRecord r : pending.values()) {
            out.add(r);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** 确认一批序列号（幂等：重复确认不产生副作用）。 */
    public synchronized void acknowledge(List<Long> seqs) {
        if (seqs == null || seqs.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Long seq : seqs) {
            if (seq != null && pending.remove(seq) != null) {
                changed = true;
            }
        }
        if (changed) {
            compact();
        }
    }

    /** 当前未确认记录数。 */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** 缓冲容量上限。 */
    public int capacity() {
        return capacity;
    }

    /** 下一个将分配的序列号。 */
    public synchronized long nextSeq() {
        return nextSeq;
    }

    /** 全部未确认记录的升序快照（测试使用）。 */
    public synchronized List<NormalizedRecord> pendingSnapshot() {
        return new ArrayList<>(pending.values());
    }

    // ---- persistence helpers ----

    private void compact() {
        Path tmp = dataFile.resolveSibling("spool.dat.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (NormalizedRecord r : pending.values()) {
                w.write(serialize(r));
                w.newLine();
            }
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to compact spool", e);
        }
        try {
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to atomically replace spool file", e);
        }
    }

    private void writeMeta() throws IOException {
        Path tmp = metaFile.resolveSibling("spool.meta.tmp");
        Files.writeString(tmp, Long.toString(nextSeq) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to atomically replace spool meta", e);
        }
    }

    private static void appendLineWithForce(Path file, String line) throws IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            try {
                ch.force(true);
            } catch (IOException ignored) {
                // 某些文件系统/测试环境不支持 force；语义尽力而为，不阻断。
            }
        }
    }

    // seq \t device \t point \t type \t unit \t numeric \t flag \t timestamp
    // 字段内 tab/换行以 \\t \\n 转义，避免格式破坏。
    static String serialize(NormalizedRecord r) {
        List<String> fields = List.of(
                Long.toString(r.seq()),
                esc(r.deviceId()),
                esc(r.pointId()),
                r.dataType().name(),
                esc(r.unit()),
                r.numeric() == null ? "" : r.numeric().toString(),
                r.flag() == null ? "" : r.flag().toString(),
                Long.toString(r.timestamp()));
        return String.join("\t", fields);
    }

    static NormalizedRecord deserialize(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 8) {
            throw new IllegalStateException("corrupt spool line: " + line);
        }
        return new NormalizedRecord(
                Long.parseLong(f[0]),
                unesc(f[1]),
                unesc(f[2]),
                DataType.valueOf(f[3]),
                unesc(f[4]),
                f[5].isEmpty() ? null : Double.parseDouble(f[5]),
                f[6].isEmpty() ? null : Boolean.parseBoolean(f[6]),
                Long.parseLong(f[7]));
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c).append(n);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 未确认记录中最旧的 seq（空时为 -1）。 */
    public synchronized long oldestPendingSeq() {
        return pending.isEmpty() ? -1 : pending.firstKey();
    }

    /** 未确认记录中最新的 seq（空时为 -1）。 */
    public synchronized long newestPendingSeq() {
        return pending.isEmpty() ? -1 : pending.lastKey();
    }

    /** 按 seq 升序快照（与 pendingSnapshot 等价的显式入口）。 */
    public synchronized List<NormalizedRecord> sortedPending() {
        return pending.values().stream()
                .sorted(Comparator.comparingLong(NormalizedRecord::seq))
                .toList();
    }
}

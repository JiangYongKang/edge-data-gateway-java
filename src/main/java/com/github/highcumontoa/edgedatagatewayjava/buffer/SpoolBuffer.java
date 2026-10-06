package com.github.highcumontoa.edgedatagatewayjava.buffer;

import com.github.highcumontoa.edgedatagatewayjava.model.DataType;
import com.github.highcumontoa.edgedatagatewayjava.model.NormalizedRecord;
import com.github.highcumontoa.edgedatagatewayjava.model.OverflowPolicy;
import com.github.highcumontoa.edgedatagatewayjava.normalize.Normalizer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断网本地缓冲。
 *
 * <p>语义：
 * <ul>
 *   <li>每条接受的记录分配全局单调递增 {@code seq}，同一 seq 永不复用。</li>
 *   <li>{@code append} 与 {@code confirm} 互斥；{@code confirmedSeq} 只进不退，
 *       重复确认、回退确认均无效，位点从不错误回退。</li>
 *   <li>容量上限确定：{@link OverflowPolicy#DROP_OLDEST} 丢弃最旧未确认记录腾位；
 *       {@link OverflowPolicy#REJECT_NEWEST} 拒绝新记录（seq 同样消耗，形成可解释缺口）。</li>
 *   <li>WAL 追加 + 状态原子落盘，{@link #recover()} 回放后内存与磁盘一致。</li>
 * </ul>
 */
public final class SpoolBuffer {

    /**
     * 缓冲变更监听（在缓冲锁内回调）。
     *
     * <p>与缓冲联动的统计计数（accepted / delivered / droppedOldest /
     * rejectedNewest）必须在这里更新：缓冲锁释放时统计与位点已同时生效，
     * 核对器“视图→快照→视图”的切面因此不会被撕裂。锁序固定为
     * 缓冲锁 → 统计锁，任何代码不得反向嵌套。
     */
    public interface MutationListener {
        /** append 完成（接受 / 溢出丢弃 / 拒新），result 与 append 返回值一致。 */
        void onAppend(AcceptResult result, NormalizedRecord record);

        /** confirm 推进位点，confirmed 为本次新确认的记录（按 seq 升序）。 */
        void onConfirm(long confirmedSeq, List<NormalizedRecord> confirmed);
    }

    /** confirm 的确定性结果：是否推进 + 本次新确认的记录明细。 */
    public record ConfirmResult(boolean advanced, List<NormalizedRecord> confirmed) {
        public ConfirmResult {
            confirmed = List.copyOf(confirmed);
        }
    }

    private final BufferStateStore.JsonFile stateStore;
    private final Path walFile;
    private final Path walTmp;
    private final int capacity;
    private final OverflowPolicy overflowPolicy;

    private final Object lock = new Object();

    private final Deque<NormalizedRecord> pending = new ArrayDeque<>();
    private long nextSeq;
    private long confirmedSeq;
    private final Map<String, Long> lastTsByDevice = new LinkedHashMap<>();
    private volatile MutationListener mutationListener;

    /** 注册缓冲变更监听（最多一个；在启动恢复前注册）。 */
    public void setMutationListener(MutationListener listener) {
        this.mutationListener = listener;
    }

    public SpoolBuffer(BufferStateStore.JsonFile stateStore,
                       Path walFile,
                       int capacity,
                       OverflowPolicy overflowPolicy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.stateStore = stateStore;
        this.walFile = walFile;
        this.walTmp = walFile.resolveSibling(walFile.getFileName() + ".tmp");
        this.capacity = capacity;
        this.overflowPolicy = overflowPolicy;
    }

    /** 启动恢复：加载位点状态并回放 WAL，重建未确认队列。 */
    public void recover() throws IOException {
        synchronized (lock) {
            BufferStateStore state = stateStore.load();
            nextSeq = state.nextSeq();
            confirmedSeq = state.confirmedSeq();
            lastTsByDevice.clear();
            lastTsByDevice.putAll(state.lastTsByDevice());
            pending.clear();

            if (!Files.exists(walFile)) {
                return;
            }
            long maxSeq = -1L;
            try (BufferedReader r = Files.newBufferedReader(walFile, StandardCharsets.UTF_8)) {
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    if (line.isBlank()) {
                        continue;
                    }
                    NormalizedRecord rec = decodeWal(line, lineNo);
                    maxSeq = Math.max(maxSeq, rec.seq());
                    if (rec.seq() > confirmedSeq) {
                        pending.addLast(rec);
                    }
                }
            }
            if (!pending.isEmpty()) {
                NormalizedRecord last = pending.peekLast();
                if (last != null && last.seq() + 1 > nextSeq) {
                    throw new IOException("WAL seq ahead of state: walMax=" + maxSeq
                            + ", nextSeq=" + nextSeq);
                }
            }
        }
    }

    /** 接收一条记录（seq 字段将被忽略并重新分配），容量满时按策略处理。 */
    public AcceptResult append(NormalizedRecord noSeqRecord) {
        synchronized (lock) {
            long seq = nextSeq;
            NormalizedRecord candidate = withSeq(noSeqRecord, seq);
            boolean full = pending.size() >= capacity;

            List<NormalizedRecord> dropped = List.of();
            if (full) {
                if (overflowPolicy == OverflowPolicy.REJECT_NEWEST) {
                    // 新记录不入缓冲；seq 仍被消耗以保证全局单调、缺口可解释
                    nextSeq++;
                    persistState();
                    AcceptResult rejected = AcceptResult.rejectedNewest(candidate);
                    notifyAppend(rejected, candidate);
                    return rejected;
                }
                // DROP_OLDEST：弹出最旧的未确认记录，直到腾出位置
                dropped = new ArrayList<>();
                while (pending.size() >= capacity) {
                    NormalizedRecord victim = pending.pollFirst();
                    if (victim == null) {
                        break;
                    }
                    dropped.add(victim);
                }
            }

            appendWal(candidate);
            pending.addLast(candidate);
            nextSeq++;
            lastTsByDevice.put(candidate.deviceId(), candidate.timestamp());

            if (!dropped.isEmpty()) {
                long newWatermark = dropped.get(dropped.size() - 1).seq();
                if (newWatermark > confirmedSeq) {
                    confirmedSeq = newWatermark;
                }
                rewriteWal();
            }
            persistState();
            AcceptResult result = dropped.isEmpty() ? AcceptResult.ok(seq)
                    : AcceptResult.droppedOldest(seq, dropped);
            notifyAppend(result, candidate);
            return result;
        }
    }

    /** 确认位点推进。重复确认/回退确认返回 false 且不产生任何效果。 */
    public boolean confirm(long seq) {
        return confirmWithRecords(seq).advanced();
    }

    /** 确认位点推进并返回本次新确认的记录明细（供逐设备送达统计与日志）。 */
    public ConfirmResult confirmWithRecords(long seq) {
        synchronized (lock) {
            if (seq <= confirmedSeq) {
                return new ConfirmResult(false, List.of());
            }
            if (seq >= nextSeq) {
                // 不能确认尚未分配的位点
                return new ConfirmResult(false, List.of());
            }
            List<NormalizedRecord> confirmed = new ArrayList<>();
            while (!pending.isEmpty() && pending.peekFirst().seq() <= seq) {
                confirmed.add(pending.pollFirst());
            }
            confirmedSeq = seq;
            rewriteWal();
            persistState();
            ConfirmResult result = new ConfirmResult(true, confirmed);
            MutationListener listener = mutationListener;
            if (listener != null) {
                listener.onConfirm(seq, result.confirmed());
            }
            return result;
        }
    }

    /** 取出所有未确认记录（按 seq 升序）的快照副本。 */
    public List<NormalizedRecord> pending() {
        synchronized (lock) {
            return new ArrayList<>(pending);
        }
    }

    public long nextSeq() {
        synchronized (lock) {
            return nextSeq;
        }
    }

    public long confirmedSeq() {
        synchronized (lock) {
            return confirmedSeq;
        }
    }

    public int size() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public Long lastTimestamp(String deviceId) {
        synchronized (lock) {
            return lastTsByDevice.get(deviceId);
        }
    }

    /** 位点与积压的一致性视图（含逐设备积压计数），供统计核对交叉验证。 */
    public BufferView view() {
        synchronized (lock) {
            Map<String, Integer> byDevice = new LinkedHashMap<>();
            for (NormalizedRecord r : pending) {
                byDevice.merge(r.deviceId(), 1, Integer::sum);
            }
            return new BufferView(nextSeq, confirmedSeq, pending.size(), byDevice);
        }
    }

    // ---- 持久化 ----

    private void notifyAppend(AcceptResult result, NormalizedRecord record) {
        MutationListener listener = mutationListener;
        if (listener != null) {
            listener.onAppend(result, record);
        }
    }

    private void persistState() {
        try {
            stateStore.save(new BufferStateStore(nextSeq, confirmedSeq,
                    new LinkedHashMap<>(lastTsByDevice)));
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist buffer state", e);
        }
    }

    private void appendWal(NormalizedRecord rec) {
        try {
            if (walFile.getParent() != null) {
                Files.createDirectories(walFile.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(walFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(encodeWal(rec));
                w.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to append WAL", e);
        }
    }

    /** 用当前 pending 快照原子重写 WAL（确认/丢弃后压缩，文件不无限增长）。 */
    private void rewriteWal() {
        try {
            if (walFile.getParent() != null) {
                Files.createDirectories(walFile.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(walTmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (NormalizedRecord rec : pending) {
                    w.write(encodeWal(rec));
                    w.newLine();
                }
                w.flush();
            }
            Files.move(walTmp, walFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("failed to compact WAL", e);
        }
    }

    // WAL 行格式：RECORD|seq|deviceId|pointId|TYPE|value|unit|timestamp
    private static String encodeWal(NormalizedRecord r) {
        return String.join("|",
                "RECORD",
                Long.toString(r.seq()),
                enc(r.deviceId()),
                enc(r.pointId()),
                r.type().name(),
                enc(String.valueOf(r.value())),
                enc(r.unit()),
                Long.toString(r.timestamp()));
    }

    private static NormalizedRecord decodeWal(String line, int lineNo) throws IOException {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 8 || !"RECORD".equals(parts[0])) {
            throw new IOException("corrupt WAL line " + lineNo + ": " + line);
        }
        try {
            long seq = Long.parseLong(parts[1]);
            DataType type = DataType.valueOf(parts[4]);
            Object value = switch (type) {
                case INT -> Integer.valueOf(parts[5]);
                case DOUBLE -> Double.valueOf(parts[5]);
                case BOOLEAN -> Boolean.valueOf(parts[5]);
            };
            return new NormalizedRecord(seq, dec(parts[2]), dec(parts[3]), type, value,
                    dec(parts[6]), Long.parseLong(parts[7]));
        } catch (RuntimeException e) {
            throw new IOException("corrupt WAL line " + lineNo + ": " + line, e);
        }
    }

    private static String enc(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String dec(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '\\' -> sb.append('\\');
                    case 'p' -> sb.append('|');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(c).append(n);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static NormalizedRecord withSeq(NormalizedRecord r, long seq) {
        return new NormalizedRecord(seq, r.deviceId(), r.pointId(), r.type(), r.value(),
                r.unit(), r.timestamp());
    }

    // Normalizer.SEQ_UNASSIGNED 语义引用，避免魔法数
    @SuppressWarnings("unused")
    private static final long SEQ_UNASSIGNED = Normalizer.SEQ_UNASSIGNED;
}

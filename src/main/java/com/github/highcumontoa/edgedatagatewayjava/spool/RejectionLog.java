package com.github.highcumontoa.edgedatagatewayjava.spool;

import com.github.highcumontoa.edgedatagatewayjava.model.RawReading;
import com.github.highcumontoa.edgedatagatewayjava.model.RejectedReading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 被拒绝读数的持久化日志（JSON Lines，{@code rejections.jsonl}）。
 *
 * <p>每条畸形读数都产生一条结构化记录：原始读数 + 可区分原因 + 说明 + 拒绝时间，
 * 追加后 force 落盘；既不静默丢弃，也绝不进入上行缓冲（不写脏值）。
 */
public class RejectionLog {

    private Path file;

    public void init(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            this.file = dataDir.resolve("rejections.jsonl");
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to init rejection log in " + dataDir, e);
        }
    }

    /** 追加一条拒绝记录并强制落盘。 */
    public synchronized void append(RejectedReading rejected) {
        String json = toJson(rejected) + System.lineSeparator();
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            try {
                ch.force(true);
            } catch (IOException ignored) {
                // 尽力而为落盘
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append rejection", e);
        }
    }

    /** 读取全部拒绝记录（主要用于测试断言）。 */
    public synchronized List<RejectedReading> loadAll() {
        List<RejectedReading> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(fromJson(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read rejection log", e);
        }
        return out;
    }

    static String toJson(RejectedReading r) {
        RawReading raw = r.raw();
        return "{"
                + "\"rejectedAt\":" + r.rejectedAt() + ","
                + "\"reason\":\"" + esc(r.reason().name()) + "\","
                + "\"detail\":\"" + esc(r.detail()) + "\","
                + "\"raw\":{"
                + "\"deviceId\":\"" + esc(raw.deviceId()) + "\","
                + "\"pointId\":\"" + esc(raw.pointId()) + "\","
                + "\"value\":" + jsonValue(raw.value()) + ","
                + "\"unit\":" + (raw.unit() == null ? "null" : "\"" + esc(raw.unit()) + "\"") + ","
                + "\"timestamp\":" + raw.timestamp()
                + "}}";
    }

    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + esc(value.toString()) + "\"";
    }

    // ---- 极简 JSON 解析（仅覆盖本类写出的固定结构） ----

    @SuppressWarnings("unchecked")
    static RejectedReading fromJson(String json) {
        java.util.Map<String, Object> root = parseObject(json);
        java.util.Map<String, Object> raw = (java.util.Map<String, Object>) root.get("raw");
        Object tsObj = raw.get("timestamp");
        Object value = raw.get("value");
        RawReading reading = new RawReading(
                (String) raw.get("deviceId"),
                (String) raw.get("pointId"),
                value,
                (String) raw.get("unit"),
                tsObj == null ? null : ((Number) tsObj).longValue());
        return new RejectedReading(reading,
                com.github.highcumontoa.edgedatagatewayjava.model.RejectReason.valueOf(
                        (String) root.get("reason")),
                (String) root.get("detail"),
                ((Number) root.get("rejectedAt")).longValue());
    }

    /**
     * 递归下降的极简 JSON object 解析。
     * 为避免数值/字符串歧义，数值字符串无法还原，故数字在序列化时已加 __NUM__ 前缀……
     * 这里实际直接解析 JSON 数字为 Double。
     */
    private static java.util.Map<String, Object> parseObject(String json) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        int[] pos = {0};
        expect(json, pos, '{');
        skipWs(json, pos);
        if (peek(json, pos) == '}') {
            pos[0]++;
            return map;
        }
        while (true) {
            skipWs(json, pos);
            String key = parseString(json, pos);
            skipWs(json, pos);
            expect(json, pos, ':');
            skipWs(json, pos);
            Object value = parseValue(json, pos);
            map.put(key, value);
            skipWs(json, pos);
            char c = next(json, pos);
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                break;
            }
            throw new IllegalArgumentException("expected , or } at " + pos[0]);
        }
        return map;
    }

    private static Object parseValue(String json, int[] pos) {
        char c = peek(json, pos);
        if (c == '"') {
            return parseString(json, pos);
        }
        if (c == '{') {
            return parseObjectAt(json, pos);
        }
        if (c == 'n') {
            pos[0] += 4;
            return null;
        }
        if (c == 't') {
            pos[0] += 4;
            return Boolean.TRUE;
        }
        if (c == 'f') {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        int start = pos[0];
        while (pos[0] < json.length() && "-+0123456789.eE".indexOf(peek(json, pos)) >= 0) {
            pos[0]++;
        }
        String token = json.substring(start, pos[0]);
        if (token.contains(".") || token.contains("e") || token.contains("E")) {
            return Double.parseDouble(token);
        }
        return Long.parseLong(token);
    }

    private static java.util.Map<String, Object> parseObjectAt(String json, int[] pos) {
        int start = pos[0];
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    pos[0] = i + 1;
                    return parseObject(json.substring(start, i + 1));
                }
            }
        }
        throw new IllegalArgumentException("unterminated object");
    }

    private static String parseString(String json, int[] pos) {
        expect(json, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next(json, pos);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next(json, pos);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private static void skipWs(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static char peek(String json, int[] pos) {
        if (pos[0] >= json.length()) {
            throw new IllegalArgumentException("unexpected end of json");
        }
        return json.charAt(pos[0]);
    }

    private static char next(String json, int[] pos) {
        char c = peek(json, pos);
        pos[0]++;
        return c;
    }

    private static void expect(String json, int[] pos, char expected) {
        char c = next(json, pos);
        if (c != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' but got '" + c
                    + "' at " + (pos[0] - 1));
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

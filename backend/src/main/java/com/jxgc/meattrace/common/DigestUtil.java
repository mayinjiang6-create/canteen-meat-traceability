package com.jxgc.meattrace.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 事件摘要计算工具。
 *
 * <p>规范见 docs/tech/04-合约设计.md 第 5 节，要点：
 * <ul>
 *   <li>固定 8 行、字段顺序不可变、LF 换行、末尾不带换行</li>
 *   <li>时间用 ISO-8601 带偏移（固定 +08:00）</li>
 *   <li>数量定点 3 位小数，禁止浮点误差</li>
 *   <li>payload 键名 ASCII 升序、无空格、非 ASCII 不转义</li>
 * </ul>
 *
 * <p><b>任何改动都必须先跑 {@code DigestUtilTest#goldenVector()}。</b>
 */
public final class DigestUtil {

    public static final String SCHEMA_VERSION = "v1";

    private static final DateTimeFormatter BIZ_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final int QUANTITY_SCALE = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private DigestUtil() {
    }

    /**
     * 生成规范串（核验时同样用这个方法，不得另行拼装）。
     */
    public static String canonicalText(String eventId,
                                       String batchId,
                                       String eventType,
                                       OffsetDateTime bizTime,
                                       BigDecimal quantity,
                                       String unit,
                                       Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder(320);
        sb.append(SCHEMA_VERSION).append('\n');
        sb.append("eventId:").append(eventId).append('\n');
        sb.append("batchId:").append(batchId).append('\n');
        sb.append("eventType:").append(eventType).append('\n');
        sb.append("bizTime:").append(BIZ_TIME.format(bizTime)).append('\n');
        sb.append("quantity:").append(scale(quantity)).append('\n');
        sb.append("unit:").append(unit == null || unit.isBlank() ? "kg" : unit.toLowerCase(Locale.ROOT)).append('\n');
        sb.append("payload:").append(toCanonicalJson(payload));
        return sb.toString();
    }

    /** SHA-256 → 64 位小写 hex。 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 生成 32 位 hex 事件标识。 */
    public static String newEventId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 数量定点化：3 位小数，四舍五入，空值按 0.000。 */
    public static String scale(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return v.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 规范化 JSON：键名升序、无空格、数字 3 位小数、布尔小写、非 ASCII 直出。
     */
    public static String toCanonicalJson(Object value) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            List<Object> items = new ArrayList<>();
            list.forEach(items::add);
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Number number) {
            BigDecimal bd = number instanceof BigDecimal d ? d : new BigDecimal(number.toString());
            sb.append(scale(bd));
        } else if (value instanceof Boolean bool) {
            sb.append(bool ? "true" : "false");
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
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
        sb.append('"');
    }
}

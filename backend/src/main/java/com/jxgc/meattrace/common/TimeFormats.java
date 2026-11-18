package com.jxgc.meattrace.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 入参时间解析：兼容接口文档 1.3 允许的两种写法。
 *
 * <ul>
 *   <li>{@code 2026-11-18 09:30:00}（空格分隔，最常用）</li>
 *   <li>{@code 2026-11-18T09:30:00+08:00}（ISO-8601 带偏移）</li>
 *   <li>{@code 2026-11-18}（纯日期，按当天 00:00:00）</li>
 * </ul>
 */
public final class TimeFormats {

    private static final DateTimeFormatter SPACE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private TimeFormats() {
    }

    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay();
        }
        if (value.indexOf('T') > 0) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(CN).toLocalDateTime();
            } catch (RuntimeException ignored) {
                return LocalDateTime.parse(value);
            }
        }
        return LocalDateTime.parse(value, SPACE);
    }

    public static LocalDateTime parseOrNow(String text) {
        LocalDateTime parsed = parse(text);
        return parsed == null ? LocalDateTime.now() : parsed;
    }
}

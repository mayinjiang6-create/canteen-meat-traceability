package com.jxgc.meattrace.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件 payload 构造工具。
 *
 * <p>为什么不用 {@code Map.of}：它不接受 null 值，而业务字段（如许可证号）常常为空。
 * 这里直接把 null 值跳过——规范串里少一个字段比出现 {@code "licenseNo":null} 更干净，
 * 也避免核验时因为"某个字段从有值变成 null"导致历史摘要对不上。
 */
public final class Payloads {

    private Payloads() {
    }

    /** 用法：{@code Payloads.of("acceptNo", no, "result", "PASS")}，key 与 value 交替传入。 */
    public static Map<String, Object> of(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("payload 参数必须成对出现");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            Object key = keyValuePairs[i];
            Object value = keyValuePairs[i + 1];
            if (key == null || value == null) {
                continue;
            }
            map.put(String.valueOf(key), value);
        }
        return map;
    }
}

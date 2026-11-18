package com.jxgc.meattrace.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摘要规范的金标准测试。
 *
 * <p>基准向量见 docs/tech/04-合约设计.md 5.5：任何规范化逻辑的改动，
 * 只要这里红了，就说明历史事件会被误判为"记录不一致"。
 */
class DigestUtilTest {

    private static final String EVENT_ID =
            "6f1c2b9a4e7d5083c1a2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";
    private static final String EXPECTED_DIGEST =
            "16decfa5e4ccb17cc948c8d3b44c59439fcd7244e2280a0a6cf5a5f86bc770bf";
    private static final int EXPECTED_BYTES = 294;

    private Map<String, Object> payload() {
        // 故意乱序放入，验证序列化时会按 ASCII 升序重排
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("temperature", new BigDecimal("3.5"));
        p.put("result", "PASS");
        p.put("inspector", "李四");
        p.put("certComplete", true);
        p.put("acceptNo", "AC20261118001");
        return p;
    }

    @Test
    @DisplayName("基准向量：规范串字节数与摘要必须逐位一致")
    void goldenVector() {
        String canonical = DigestUtil.canonicalText(
                EVENT_ID,
                "PB20261118001",
                "ACCEPT_RESULT",
                OffsetDateTime.of(2026, 11, 18, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
                new BigDecimal("120.000"),
                "kg",
                payload());

        assertEquals(EXPECTED_BYTES, canonical.getBytes(StandardCharsets.UTF_8).length,
                "规范串字节数变了，说明字段顺序或格式被改动");
        assertEquals(EXPECTED_DIGEST, DigestUtil.sha256Hex(canonical),
                "摘要不一致：规范化逻辑被改坏了，历史事件会全部误判");
    }

    @Test
    @DisplayName("数量定点化：3 位小数、四舍五入、空值按 0")
    void quantityScale() {
        assertEquals("120.000", DigestUtil.scale(new BigDecimal("120")));
        assertEquals("120.500", DigestUtil.scale(new BigDecimal("120.5")));
        assertEquals("120.001", DigestUtil.scale(new BigDecimal("120.0006")));
        assertEquals("0.000", DigestUtil.scale(null));
        assertEquals("-1.500", DigestUtil.scale(new BigDecimal("-1.5")));
    }

    @Test
    @DisplayName("payload 序列化：键升序、无空格、数字补位、中文直出")
    void canonicalJson() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("b", 2);
        p.put("a", "值");
        assertEquals("{\"a\":\"值\",\"b\":2.000}", DigestUtil.toCanonicalJson(p));
    }

    @Test
    @DisplayName("同内容必得同摘要")
    void deterministic() {
        OffsetDateTime t = OffsetDateTime.of(2026, 11, 18, 9, 30, 0, 0, ZoneOffset.ofHours(8));
        String a = DigestUtil.sha256Hex(DigestUtil.canonicalText(
                EVENT_ID, "PB20261118001", "ACCEPT_RESULT", t, new BigDecimal("120"), "kg", payload()));
        String b = DigestUtil.sha256Hex(DigestUtil.canonicalText(
                EVENT_ID, "PB20261118001", "ACCEPT_RESULT", t, new BigDecimal("120.000"), "KG", payload()));
        assertEquals(a, b, "数量写 120 与 120.000、单位写 kg 与 KG，摘要必须一致");
    }

    @Test
    @DisplayName("newEventId 必须是 32 字节 → 64 位小写 hex")
    void newEventIdFormat() {
        String id = DigestUtil.newEventId();
        assertEquals(64, id.length(), "必须是 64 位 hex，才能对齐合约 bytes32 与 CHAR(64)");
        assertTrue(id.matches("[0-9a-f]{64}"), "只能是小写 hex");
        assertNotEquals(id, DigestUtil.newEventId(), "两次生成不能相同");
    }
}

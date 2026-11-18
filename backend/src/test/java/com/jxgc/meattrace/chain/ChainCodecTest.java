package com.jxgc.meattrace.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ABI 编码与返回码解析。不依赖链，纯离线可跑。 */
class ChainCodecTest {

    private static final String EVENT_ID =
            "6f1c2b9a4e7d5083c1a2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";

    @Test
    @DisplayName("query calldata = 0x + selector + eventId（74 字符）")
    void queryCalldata() {
        String data = ChainCodec.queryCalldata(EVENT_ID);
        assertEquals("0x" + ChainCodec.QUERY_SELECTOR + EVENT_ID, data);
        assertEquals(74, data.length());
        assertTrue(data.startsWith("0xa3b61a9c"));
    }

    @Test
    @DisplayName("eventId 带 0x 前缀也能正确拼")
    void queryCalldataWithPrefix() {
        assertEquals("0x" + ChainCodec.QUERY_SELECTOR + EVENT_ID,
                ChainCodec.queryCalldata("0x" + EVENT_ID));
    }

    @Test
    @DisplayName("eventId 位数不对要立刻报错，不能悄悄拼错")
    void rejectBadEventId() {
        assertThrows(IllegalArgumentException.class, () -> ChainCodec.queryCalldata("abcd"));
    }

    @Test
    @DisplayName("从 output 末字节取返回码：0 新增 / 1 幂等 / 2 冲突")
    void returnCode() {
        String padded = "0x" + "0".repeat(62);
        assertEquals(ChainCodec.RESULT_APPENDED, ChainCodec.returnCode(padded + "00"));
        assertEquals(ChainCodec.RESULT_DUPLICATE, ChainCodec.returnCode(padded + "01"));
        assertEquals(ChainCodec.RESULT_CONFLICT, ChainCodec.returnCode(padded + "02"));
    }

    @Test
    @DisplayName("output 为空或非法要抛 ChainUnavailableException")
    void rejectBadOutput() {
        assertThrows(ChainUnavailableException.class, () -> ChainCodec.returnCode(""));
        assertThrows(ChainUnavailableException.class, () -> ChainCodec.returnCode(null));
        assertThrows(ChainUnavailableException.class, () -> ChainCodec.returnCode("0xzz"));
    }
}

package com.jxgc.meattrace.module.trace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jxgc.meattrace.chain.ChainEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 四类核验状态的判定。
 *
 * <p>这是全项目最需要"钉死"的逻辑：它不是普通的功能点，而是
 * "存证系统"与"假存证系统"的分界线。任何改动都必须让这组测试保持绿色。
 */
class VerifyStatusResolverTest {

    private static final String DIGEST =
            "16decfa5e4ccb17cc948c8d3b44c59439fcd7244e2280a0a6cf5a5f86bc770bf";

    private static ChainEvent event(String digest) {
        return new ChainEvent("PB20261118001", "ACCEPT_RESULT", 1794965400L,
                digest, "0x0bd53242ca7ee7fb0ce57a3cb296b6d4b237dbe8", 1795000509666L, true);
    }

    @Test
    @DisplayName("链上有且摘要一致 → MATCHED")
    void matched() {
        assertEquals(VerifyStatusResolver.MATCHED,
                VerifyStatusResolver.resolve(DIGEST, Optional.of(event(DIGEST))));
    }

    @Test
    @DisplayName("链上有但摘要不同 → MISMATCHED")
    void mismatched() {
        String tampered = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        assertEquals(VerifyStatusResolver.MISMATCHED,
                VerifyStatusResolver.resolve(tampered, Optional.of(event(DIGEST))));
    }

    @Test
    @DisplayName("链正常响应但查不到 → UNCONFIRMED")
    void unconfirmed() {
        assertEquals(VerifyStatusResolver.UNCONFIRMED,
                VerifyStatusResolver.resolve(DIGEST, Optional.empty()));
    }

    @Test
    @DisplayName("摘要大小写与 0x 前缀不影响判定")
    void normalizeCase() {
        assertEquals(VerifyStatusResolver.MATCHED,
                VerifyStatusResolver.resolve(DIGEST.toUpperCase(),
                        Optional.of(event("0x" + DIGEST))));
    }

    @Test
    @DisplayName("汇总：全部 MATCHED 才是 PASS")
    void overallPass() {
        assertEquals("PASS", VerifyStatusResolver.overall(3, 0, 0, 0));
    }

    @Test
    @DisplayName("汇总：只要有一个 MISMATCHED 就是 FAIL")
    void overallFail() {
        assertEquals("FAIL", VerifyStatusResolver.overall(3, 1, 0, 0));
    }

    @Test
    @DisplayName("汇总：UNREACHABLE 只降级为 PARTIAL，绝不判 FAIL")
    void overallPartialWhenUnreachable() {
        assertEquals("PARTIAL", VerifyStatusResolver.overall(0, 0, 0, 1));
        assertEquals("PARTIAL", VerifyStatusResolver.overall(2, 0, 1, 1));
    }
}

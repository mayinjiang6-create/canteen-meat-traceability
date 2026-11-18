package com.jxgc.meattrace.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真实链上的读路径验证（需要虚拟机在跑，否则自动跳过）。
 *
 * <p>断言的是 2026-11-18 实测写入的那条事件：
 * eventId 6f1c…0718、批次 PB20261118001、类型 ACCEPT_RESULT、
 * 摘要 16decfa5…70bf、区块 5。
 */
class RpcReadClientChainTest {

    private static final String RPC_URL = "http://192.168.239.130:8545";
    private static final String CONTRACT = "0xd24180cc0fef2f3e545de4f9aafc09345cd08903";
    private static final String EVENT_ID =
            "6f1c2b9a4e7d5083c1a2b3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";
    private static final String EXPECTED_DIGEST =
            "16decfa5e4ccb17cc948c8d3b44c59439fcd7244e2280a0a6cf5a5f86bc770bf";
    /** keccak256("query(bytes32)") 前 4 字节。 */
    private static final String SELECTOR_QUERY = "a3b61a9c";

    private boolean chainReachable() {
        URI uri = URI.create(RPC_URL);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private RpcReadClient client() {
        ChainProperties props = new ChainProperties();
        props.getRpc().setUrl(RPC_URL);
        props.getRpc().setTimeoutMs(5000);
        return new RpcReadClient(props);
    }

    @Test
    @DisplayName("读链：块高可查")
    void blockNumber() {
        assumeTrue(chainReachable(), "链不可达，跳过（虚拟机未启动或 web3_rpc 未开启）");
        assertTrue(client().blockNumber() > 0);
    }

    @Test
    @DisplayName("读链：已存证事件的七个字段与写入时一致")
    void readsKnownEvent() {
        assumeTrue(chainReachable(), "链不可达，跳过");

        Optional<ChainEvent> found =
                client().queryEvent(CONTRACT, "0x" + SELECTOR_QUERY + EVENT_ID);

        assertTrue(found.isPresent(), "链上应存在该事件");
        ChainEvent event = found.get();
        assertEquals("PB20261118001", event.batchId());
        assertEquals("ACCEPT_RESULT", event.eventType());
        assertEquals(1794965400L, event.bizTime(), "bizTime 必须是 2026-11-18 09:30 +08:00");
        assertEquals(EXPECTED_DIGEST, event.dataDigest());
        assertTrue(event.submitter().toLowerCase().endsWith("dbe8"));
    }

    @Test
    @DisplayName("读链：交易回执可查")
    void readsReceipt() {
        assumeTrue(chainReachable(), "链不可达，跳过");

        var receipt = client().receipt(
                "0xcf8c6e6fa1daf587fdcfb0cb8e978b340f417956db83e627387e2e2af927b95a");
        assertEquals("0x1", receipt.get("status"));
        assertEquals(5L, receipt.get("blockNumber"));
    }
}

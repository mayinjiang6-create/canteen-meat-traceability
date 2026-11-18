package com.jxgc.meattrace.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 只读链客户端：走节点标准 JSON-RPC（eth_call / eth_getTransactionReceipt）。
 *
 * <p>为什么不用 WeBASE-Front 的 {@code /trans/query-transaction}：
 * 该接口在 WeBASE 3.1.1 + solc 0.8 ABI 下必现空指针，详见
 * {@code docs/tech/10-链环境实测记录.md} 第 5 节。
 *
 * <p>读走 RPC 的额外好处：不产生交易、毫秒级返回，公开溯源页一次核验 6 条事件也不会拖慢。
 */
@Component
public class RpcReadClient {

    private static final Logger log = LoggerFactory.getLogger(RpcReadClient.class);

    private final String url;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public RpcReadClient(ChainProperties props) {
        this.url = props.getRpc().getUrl();
        this.timeout = Duration.ofMillis(props.getRpc().getTimeoutMs());
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 当前块高。 */
    public long blockNumber() {
        JsonNode result = call("eth_blockNumber", mapper.createArrayNode());
        return Long.parseLong(strip0x(result.asText()), 16);
    }

    /** 执行只读调用，返回原始 ABI 编码结果（带 0x）。 */
    public String ethCall(String to, String data) {
        var params = mapper.createArrayNode();
        var tx = mapper.createObjectNode();
        tx.put("to", to);
        tx.put("data", data.startsWith("0x") ? data : "0x" + data);
        params.add(tx);
        params.add("latest");
        JsonNode result = call("eth_call", params);
        return result.asText();
    }

    /** 交易回执。 */
    public Map<String, Object> receipt(String txHash) {
        JsonNode result = call("eth_getTransactionReceipt", mapper.createArrayNode().add(txHash));
        Map<String, Object> brief = new HashMap<>();
        brief.put("transactionHash", result.path("transactionHash").asText(null));
        brief.put("blockNumber", result.path("blockNumber").isMissingNode()
                ? null : Long.parseLong(strip0x(result.path("blockNumber").asText()), 16));
        brief.put("status", result.path("status").asText(null));
        brief.put("gasUsed", result.path("gasUsed").isMissingNode()
                ? null : Long.parseLong(strip0x(result.path("gasUsed").asText()), 16));
        brief.put("from", result.path("from").asText(null));
        brief.put("to", result.path("to").asText(null));
        return brief;
    }

    /**
     * 读一条事件；链上不存在时返回 {@link Optional#empty()}。
     *
     * <p><b>异常语义</b>：链不可达或返回数据畸形时抛 {@link ChainUnavailableException}，
     * 调用方据此判定 {@code UNREACHABLE}；只有正常响应且 {@code exists=false} 才是
     * {@code UNCONFIRMED}。两者混淆会把"链挂了"误读成"数据造假"，是业务红线。
     */
    public Optional<ChainEvent> queryEvent(String contractAddress, String encodedData) {
        String hex = ethCall(contractAddress, encodedData);
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(strip0x(hex));
        } catch (IllegalArgumentException e) {
            throw new ChainUnavailableException("链上返回不是合法 hex：" + hex, e);
        }
        if (raw.length < 32 * 7) {
            throw new ChainUnavailableException(
                    "链上返回数据长度异常：" + raw.length + " 字节（query 应返回 7 个 32 字节字）");
        }
        ChainEvent event = decodeQueryResult(hex);
        return event.exists() ? Optional.of(event) : Optional.empty();
    }

    /** 按 eventId 读一条事件，内部用 {@link ChainCodec} 拼 calldata。 */
    public Optional<ChainEvent> queryEventByEventId(String contractAddress, String eventId) {
        return queryEvent(contractAddress, ChainCodec.queryCalldata(eventId));
    }

    /**
     * 解 {@code query(bytes32)} 的 7 个返回值。
     * 布局：word0 batchId 偏移、word1 eventType 偏移、word2 bizTime、
     * word3 digest、word4 submitter、word5 createdAt、word6 exists。
     */
    public ChainEvent decodeQueryResult(String hex) {
        byte[] data = HexFormat.of().parseHex(strip0x(hex));
        return new ChainEvent(
                readString(data, word(data, 0)),
                readString(data, word(data, 1)),
                readUint(word(data, 2)),
                HexFormat.of().formatHex(word(data, 3)),
                "0x" + HexFormat.of().formatHex(data, 12 + 32 * 4, 32 + 32 * 4),
                readUint(word(data, 5)),
                readUint(word(data, 6)) != 0);
    }

    private JsonNode call(String method, com.fasterxml.jackson.databind.node.ArrayNode params) {
        try {
            var body = mapper.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            body.set("params", params);
            body.put("id", 1);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode node = mapper.readTree(response.body());
            if (node.hasNonNull("error")) {
                throw new ChainUnavailableException("RPC 返回错误: " + node.get("error"));
            }
            return node.get("result");
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("RPC 调用失败 method={}", method, e);
            throw new ChainUnavailableException("链上查询不可达: " + e.getMessage(), e);
        }
    }

    private static String strip0x(String s) {
        return s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
    }

    private static byte[] word(byte[] data, int index) {
        byte[] out = new byte[32];
        System.arraycopy(data, index * 32, out, 0, 32);
        return out;
    }

    private static long readUint(byte[] word) {
        long v = 0;
        for (byte b : word) {
            v = (v << 8) | (b & 0xFF);
        }
        return v;
    }

    private static String readString(byte[] data, byte[] offsetWord) {
        int offset = (int) readUint(offsetWord);
        int length = (int) readUint(word(data, offset / 32));
        return new String(data, offset + 32, length, StandardCharsets.UTF_8);
    }
}

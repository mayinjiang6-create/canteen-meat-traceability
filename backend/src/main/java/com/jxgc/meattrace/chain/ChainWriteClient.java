package com.jxgc.meattrace.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 链写入客户端：走 WeBASE-Front {@code POST /trans/handleWithSign}，
 * 私钥由 WeBASE-Sign 托管（符合"统一演示账户"的要求）。
 *
 * <p>为什么不走 java-sdk：见 TECH-10 与 README 的技术选型说明。
 *
 * <p><b>异常语义</b>（决定 worker 能不能安全重试）：
 * <ul>
 *   <li>{@link ChainRejectedException}：链上明确拒绝，**确定没写进去**，可直接重试</li>
 *   <li>{@link ChainUnavailableException}：网络超时等，**不知道写没写**，重试前必须先查链（BR-14）</li>
 * </ul>
 */
@Component
public class ChainWriteClient {

    private static final Logger log = LoggerFactory.getLogger(ChainWriteClient.class);
    private static final String APPEND = "append";

    private final ChainProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode abi;

    public ChainWriteClient(ChainProperties properties) {
        this.properties = properties;
        var webase = properties.getWebase();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(webase.getConnectTimeoutMs()))
                .build();
        try (InputStream in = new ClassPathResource("contract/TraceRegistry.abi.json").getInputStream()) {
            this.abi = mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读不到合约 ABI（classpath:contract/TraceRegistry.abi.json），请确认打包时已复制", e);
        }
    }

    /**
     * 追加一条事件存证。
     *
     * @param bizTimeSeconds 业务时间的 Unix **秒**（不是毫秒）
     * @return 含返回码的结果；返回码 0=新增、1=幂等、2=冲突
     */
    public AppendResult append(String eventId, String batchId, String eventType,
                               long bizTimeSeconds, String dataDigest) {
        var webase = properties.getWebase();
        if (webase.getContractAddress() == null || webase.getContractAddress().isBlank()) {
            throw new ChainUnavailableException("未配置 chain.webase.contract-address，无法上链");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("groupId", webase.getGroupId());
        body.put("contractName", webase.getContractName());
        body.put("contractPath", webase.getContractPath());
        body.put("contractAddress", webase.getContractAddress());
        body.set("contractAbi", abi);
        body.put("funcName", APPEND);

        ArrayNode params = mapper.createArrayNode();
        // eventId / dataDigest 在 handleWithSign 里不带 0x；bizTime 传秒的字符串
        params.add(ChainCodec.strip0x(eventId));
        params.add(batchId);
        params.add(eventType);
        params.add(String.valueOf(bizTimeSeconds));
        params.add(ChainCodec.strip0x(dataDigest));
        body.set("funcParam", params);
        body.put("signUserId", webase.getSignUserId());

        String url = webase.getBaseUrl().replaceAll("/+$", "") + "/trans/handleWithSign";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(webase.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8))
                .build();

        String text;
        int status;
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = response.statusCode();
            text = response.body();
        } catch (IOException e) {
            log.warn("上链网络异常 eventId={} : {}", eventId, e.toString());
            throw new ChainUnavailableException("上链网络异常: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainUnavailableException("上链被中断", e);
        }

        if (status != 200) {
            // 5xx 既可能是服务端故障也可能是合约执行失败，无法区分 → 按"结果不明"处理
            log.warn("上链返回 HTTP {} eventId={} body={}", status, eventId, abbreviate(text));
            throw new ChainUnavailableException("WeBASE-Front 返回 HTTP " + status);
        }

        JsonNode node;
        try {
            node = mapper.readTree(text);
        } catch (IOException e) {
            throw new ChainUnavailableException("链上响应不是合法 JSON：" + abbreviate(text), e);
        }

        boolean statusOk = node.path("statusOK").asBoolean(false);
        int txStatus = node.path("status").asInt(-1);
        String txHash = node.path("transactionHash").asText(null);
        if (!statusOk || txStatus != 0) {
            String message = node.path("message").asText("unknown");
            log.warn("链上拒绝 eventId={} message={} txHash={}", eventId, message, txHash);
            throw new ChainRejectedException("链上拒绝：" + message);
        }

        int returnCode = ChainCodec.returnCode(node.path("output").asText(""));
        Long blockNumber = node.path("blockNumber").isNumber() ? node.path("blockNumber").asLong() : null;
        Long gasUsed = node.path("gasUsed").isMissingNode() ? null : node.path("gasUsed").asLong();

        log.info("上链完成 eventId={} 返回码={} txHash={} block={}", eventId, returnCode, txHash, blockNumber);
        // 注意：raw 必须存**完整**响应。tx_receipt.raw_receipt 是 JSON 列，
        // 若把它截断再插入，MySQL 会因"非法 JSON"直接报错，导致存证状态更新失败。
        return new AppendResult(txHash, returnCode, blockNumber, gasUsed, text);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}

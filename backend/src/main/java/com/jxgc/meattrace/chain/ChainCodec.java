package com.jxgc.meattrace.chain;

/**
 * ABI 编码/解码的常量与工具。
 *
 * <p><b>为什么 selector 是硬编码常量而不是现算</b>：
 * Ethereum 的 selector 是 {@code keccak256("query(bytes32)")} 的前 4 字节，
 * 而 JDK 自带的是 NIST 版 SHA3-256（padding 不同），算不出一样的值。
 * 本项目读链只用这一个函数、且参数是定长的 {@code bytes32}，
 * 所以直接写常量最省事——它已在 {@code RpcReadClientChainTest} 里连真链验证过。
 * 将来若新增读函数，用 WeBASE 的 {@code /trans/encodeFunction} 取一次编码再写常量即可。
 */
public final class ChainCodec {

    /** keccak256("query(bytes32)") 前 4 字节。 */
    public static final String QUERY_SELECTOR = "a3b61a9c";

    /** 合约 append 的返回码。 */
    public static final int RESULT_APPENDED = 0;
    public static final int RESULT_DUPLICATE = 1;
    public static final int RESULT_CONFLICT = 2;

    private ChainCodec() {
    }

    /**
     * 拼 {@code query(bytes32)} 的 calldata：0x + selector + 32 字节 eventId。
     *
     * <p>参数是定长 bytes32，不需要动态 offset，所以直接拼即可。
     */
    public static String queryCalldata(String eventId) {
        String id = strip0x(eventId);
        if (id.length() != 64) {
            throw new IllegalArgumentException("eventId 必须是 64 位 hex，实际 " + id.length() + " 位");
        }
        return "0x" + QUERY_SELECTOR + id;
    }

    /**
     * 从 {@code handleWithSign} 响应的 {@code output} 里取 append 的返回码。
     *
     * <p>{@code output} 是 ABI 编码的 uint8，实际是 32 字节，真实值在**最后一个字节**。
     */
    public static int returnCode(String output) {
        String hex = strip0x(output == null ? "" : output);
        if (hex.length() < 2) {
            throw new ChainUnavailableException("链上响应 output 为空，无法判定返回码");
        }
        try {
            return Integer.parseInt(hex.substring(hex.length() - 2), 16);
        } catch (NumberFormatException e) {
            throw new ChainUnavailableException("链上响应 output 非法：" + output, e);
        }
    }

    public static String strip0x(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }
}

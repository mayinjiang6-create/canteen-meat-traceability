package com.jxgc.meattrace.module.trace.service;

import com.jxgc.meattrace.chain.ChainEvent;
import java.util.Locale;
import java.util.Optional;

/**
 * 四类核验状态的判定。**纯函数**，不碰数据库、不碰 HTTP。
 *
 * <p>为什么单独拎出来：这是全项目最容易被问倒的地方，也是"存证系统"和"假存证系统"的分界线。
 * 把它做成无副作用的纯函数，才能用单元测试把四种组合全覆盖。
 *
 * <table>
 *   <tr><th>状态</th><th>触发条件</th><th>业务含义</th></tr>
 *   <tr><td>MATCHED</td><td>链上有该 eventId，且链上摘要 == 本地重算摘要</td><td>记录已核验</td></tr>
 *   <tr><td>MISMATCHED</td><td>链上有，但摘要不同</td><td>记录被改过，谨慎采信</td></tr>
 *   <tr><td>UNCONFIRMED</td><td>链正常响应但查不到该 eventId</td><td>存证还在路上</td></tr>
 *   <tr><td>UNREACHABLE</td><td>链根本没响应（超时/拒绝/畸形数据）</td><td>暂无法核验，<b>不是数据有问题</b></td></tr>
 * </table>
 *
 * <p><b>红线</b>：UNREACHABLE 必须由调用方在 {@code catch (ChainUnavailableException)} 分支给出，
 * 绝不能靠"捕获一切异常然后当成 UNCONFIRMED"——那样链挂了会被误读成"数据造假"。
 */
public final class VerifyStatusResolver {

    public static final String MATCHED = "MATCHED";
    public static final String MISMATCHED = "MISMATCHED";
    public static final String UNCONFIRMED = "UNCONFIRMED";
    public static final String UNREACHABLE = "UNREACHABLE";

    private VerifyStatusResolver() {
    }

    /**
     * @param recalcDigest 本地按 {@code canonical_text} 重算出的摘要
     * @param onChain      链上查询结果；{@code Optional.empty()} 表示链正常但没这条记录
     */
    public static String resolve(String recalcDigest, Optional<ChainEvent> onChain) {
        if (onChain == null || onChain.isEmpty()) {
            return UNCONFIRMED;
        }
        String chainDigest = normalize(onChain.get().dataDigest());
        String local = normalize(recalcDigest);
        return chainDigest.equals(local) ? MATCHED : MISMATCHED;
    }

    /**
     * 汇总判定：全部 MATCHED 才是 PASS；只要有一个 MISMATCHED 就是 FAIL；其余为 PARTIAL。
     *
     * <p>注意 UNREACHABLE 只降级到 PARTIAL，不判 FAIL——链不可达不等于数据有问题。
     */
    public static String overall(long matched, long mismatched, long unconfirmed, long unreachable) {
        long total = matched + mismatched + unconfirmed + unreachable;
        if (total == 0) {
            return "PARTIAL";
        }
        if (mismatched > 0) {
            return "FAIL";
        }
        return matched == total ? "PASS" : "PARTIAL";
    }

    private static String normalize(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.toLowerCase(Locale.ROOT).replace("0x", "");
    }
}

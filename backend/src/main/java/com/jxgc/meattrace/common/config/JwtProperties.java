package com.jxgc.meattrace.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 配置，对应 application-*.yml 的 {@code jwt.*}。 */
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 签名密钥。生产环境必须通过环境变量覆盖。
     * JJWT 0.12.x 要求 HS256 密钥至少 256 bit（32 字节）。
     */
    private String secret = "meat-trace-demo-secret-please-override-in-env-2026";

    /** 令牌有效期（小时）。 */
    private int expireHours = 12;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getExpireHours() {
        return expireHours;
    }

    public void setExpireHours(int expireHours) {
        this.expireHours = expireHours;
    }
}

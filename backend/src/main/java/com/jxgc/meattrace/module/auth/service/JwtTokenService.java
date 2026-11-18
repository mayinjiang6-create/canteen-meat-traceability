package com.jxgc.meattrace.module.auth.service;

import com.jxgc.meattrace.common.BizException;
import com.jxgc.meattrace.common.ErrorCode;
import com.jxgc.meattrace.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** JWT 签发与解析（JJWT 0.12.x）。 */
@Component
public class JwtTokenService {

    private final SecretKey key;
    private final int expireHours;

    public JwtTokenService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(normalize(properties.getSecret().getBytes(StandardCharsets.UTF_8)));
        this.expireHours = properties.getExpireHours();
    }

    /** HS256 要求至少 32 字节；配置过短时用 SHA-256 派生，避免启动直接失败。 */
    private static byte[] normalize(byte[] raw) {
        if (raw.length >= 32) {
            return Arrays.copyOf(raw, 32);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    public String issue(long userId, String username, String role, Long supplierId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireHours * 3600L)))
                .signWith(key);
        if (supplierId != null) {
            builder.claim("supplierId", supplierId);
        }
        return builder.compact();
    }

    public long expiresInSeconds() {
        return expireHours * 3600L;
    }

    /** 解析令牌；任何异常统一翻译成 40101。 */
    public TokenPayload parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object supplier = claims.get("supplierId");
            return new TokenPayload(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get("role", String.class),
                    supplier == null ? null : Long.valueOf(String.valueOf(supplier)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record TokenPayload(long userId, String username, String role, Long supplierId) {
    }
}

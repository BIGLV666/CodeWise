package org.example.servicecommon.until;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具（HS 系列对称签名）。
 *
 * <p>密钥与过期时间由使用方经构造器注入（来自各服务配置，如
 * {@code jwt.secret: ${JWT_SECRET}}），无默认值——密钥不允许硬编码在源码中。
 * Python 侧 CodeWise-Agent 的 {@code JWT_SECRET} 必须与 Java 侧同值。</p>
 */
public class JwtUntil {

    private final String secret;

    private final long expirationMillis;

    public JwtUntil(String secret, long expirationMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jwt.secret 未配置（建议经环境变量 JWT_SECRET 注入）");
        }
        this.secret = secret;
        this.expirationMillis = expirationMillis;
    }

    /**
     * 生成 Token
     */
    public String generateToken(Long userId, String userName) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userName", userName);

        return Jwts.builder()
                .subject(userId.toString())
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMillis))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 解析 Token
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 获取 userId
     */
    public Long getUserIdFromToken(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    /**
     * 验证 Token 是否有效
     */
    public Boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取签名密钥（兼容 String 和 SecretKey）
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}


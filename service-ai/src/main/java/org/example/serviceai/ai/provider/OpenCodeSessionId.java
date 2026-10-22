package org.example.serviceai.ai.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * OpenCode Go 的会话标识。
 *
 * <p>上游要求会话 id 形状为 {@code ses_} + 12 位小写 hex + 14 位 base62（历史版本还有
 * {@code ses_} + 64 位 hex，一并放行）。语义是「一个会话一个稳定 id」，上游拿它当
 * prompt cache 的分组键：同一会话的每轮请求、每次重试、每次恢复都必须得到同一个值。</p>
 *
 * <p>因此哈希种子只能取<b>跨轮稳定</b>的东西（会话主键），绝不能掺入 prompt、时间戳之类
 * 每轮都变的值——那等于每轮新开会话，上游缓存永不命中，还会把它的缓存条目撑爆。</p>
 */
public final class OpenCodeSessionId {

    private static final String PREFIX = "ses_";
    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern SHAPE = Pattern.compile("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$");
    private static final Pattern LEGACY_SHAPE = Pattern.compile("^ses_[0-9a-f]{64}$");

    private OpenCodeSessionId() {
    }

    /**
     * 把稳定的会话标识规整成上游期望的形状；已经是合法形状就原样返回（幂等）。
     *
     * @return 无可用种子时返回 null，调用方据此<b>省略</b>会话头——发一个占位值比不发更糟：
     *         它会静默污染上游缓存，而不是像缺头那样明确报错
     */
    public static String toSessionId(String seed) {
        if (seed == null) {
            return null;
        }
        String value = seed.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (SHAPE.matcher(value).matches() || LEGACY_SHAPE.matcher(value).matches()) {
            return value;
        }

        byte[] digest = sha256(value);
        StringBuilder id = new StringBuilder(PREFIX);
        for (int i = 0; i < 6; i++) {
            id.append(String.format("%02x", digest[i] & 0xff));
        }
        for (int i = 6; i < 20; i++) {
            // Java 的 byte 有符号：直接取模会得到负数并让 charAt 越界，必须 floorMod
            id.append(BASE62.charAt(Math.floorMod(digest[i], BASE62.length())));
        }
        return id.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是 JDK 必备算法，走不到这里
            throw new IllegalStateException(impossible);
        }
    }
}

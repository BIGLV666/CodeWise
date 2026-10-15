package org.example.serviceai.ai.provider;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 id 的形状与确定性。
 *
 * <p>确定性是硬要求：同一会话的每轮请求必须算出同一个 id，否则上游的 prompt cache 永不命中
 * （表现为"没报错但一直慢、一直计费"），所以这里既钉形状也钉"同入同出"。</p>
 */
class OpenCodeSessionIdTest {

    private static final String SHAPE = "^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$";

    @Test
    void alreadyShapedIdIsReturnedAsIs() {
        String modern = "ses_206f84f18ffeZ6hhD7pFYAiW5T";
        assertEquals(modern, OpenCodeSessionId.toSessionId(modern));

        String legacy = "ses_" + "a".repeat(64);
        assertEquals(legacy, OpenCodeSessionId.toSessionId(legacy));
    }

    @Test
    void arbitrarySeedIsNormalizedIntoTheExpectedShape() {
        String id = OpenCodeSessionId.toSessionId("conversation-42");

        assertTrue(id.matches(SHAPE), id);
        assertEquals("ses_".length() + 12 + 14, id.length());
    }

    @Test
    void sameSeedAlwaysProducesTheSameId() {
        // 跨轮稳定：同一会话的每一轮、每一次重试都必须得到同一个值
        assertEquals(
                OpenCodeSessionId.toSessionId("conversation-42"),
                OpenCodeSessionId.toSessionId("conversation-42"));
        assertEquals(
                OpenCodeSessionId.toSessionId("conversation-42"),
                OpenCodeSessionId.toSessionId("  conversation-42  "));
    }

    @Test
    void differentSeedsProduceDifferentIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ids.add(OpenCodeSessionId.toSessionId("conversation-" + i));
        }

        assertEquals(500, ids.size());
        assertNotEquals(
                OpenCodeSessionId.toSessionId("conversation-1"),
                OpenCodeSessionId.toSessionId("conversation-2"));
    }

    @Test
    void missingSeedYieldsNullSoCallersOmitInsteadOfFaking() {
        // 发一个占位值会静默污染上游缓存，比缺头（明确 400）更难查，所以这里选择"没有就是没有"
        assertNull(OpenCodeSessionId.toSessionId(null));
        assertNull(OpenCodeSessionId.toSessionId(""));
        assertNull(OpenCodeSessionId.toSessionId("   "));
    }

    /**
     * base62 取值不越界。
     *
     * <p>Java 的 {@code byte} 有符号，若照抄 Go 的 {@code int(b) % 62} 会得到负下标、
     * 在 {@code charAt} 上抛 {@link StringIndexOutOfBoundsException}。随机灌一批种子把它压出来。</p>
     */
    @Test
    void base62IndexNeverEscapesTheAlphabet() {
        Random random = new Random(20261015L);
        for (int i = 0; i < 5000; i++) {
            String seed = "conversation-" + random.nextLong();
            String id = OpenCodeSessionId.toSessionId(seed);
            assertTrue(id.matches(SHAPE), seed + " -> " + id);
        }
    }
}

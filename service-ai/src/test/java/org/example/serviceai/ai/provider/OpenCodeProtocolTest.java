package org.example.serviceai.ai.provider;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenCode Go 协议：会话头 + 请求体多出来的两个字段。
 *
 * <p>这里刻意把「缺会话标识」的行为钉死为<b>省略</b>而不是伪造——上游缺头会明确 400，
 * 而伪造一个占位值会静默污染它的 prompt cache，后者更难查。</p>
 */
class OpenCodeProtocolTest {

    private final AiProtocol protocol = OpenCodeProtocol.INSTANCE;

    @Test
    void protocolIsNamedAfterTheVendor() {
        assertEquals("opencode-go", protocol.name());
    }

    @Test
    void bothSessionHeadersCarryTheSameId() {
        Map<String, String> headers = protocol.perRequestHeaders("conversation-42");

        // 上游要求 x-opencode-session；两个头同时存在时必须相等，所以这里写成同一个值
        assertEquals(headers.get(OpenCodeProtocol.SESSION_HEADER),
                headers.get(OpenCodeProtocol.SESSION_ID_HEADER));
        assertTrue(headers.get(OpenCodeProtocol.SESSION_HEADER)
                .matches("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$"));
    }

    @Test
    void noSessionRefMeansNoHeadersAtAll() {
        assertTrue(protocol.perRequestHeaders(null).isEmpty());
        assertTrue(protocol.perRequestHeaders("   ").isEmpty());
    }

    @Test
    void bodyCarriesCacheKeyAndUsageOption() {
        String sessionId = OpenCodeSessionId.toSessionId("conversation-42");

        String body = protocol.buildBody("deepseek-v4.1-flash", "hi", 1024, "conversation-42");

        assertTrue(body.contains("\"prompt_cache_key\":\"" + sessionId + "\""), body);
        assertTrue(body.contains("\"include_usage\":true"), body);
        // 通用字段仍在：这是 OpenAI 兼容体，只多了两个字段
        assertTrue(body.contains("\"model\":\"deepseek-v4.1-flash\""), body);
        assertTrue(body.contains("\"stream\":true"), body);
    }

    @Test
    void bodyStillAsksForUsageWithoutASession() {
        String body = protocol.buildBody("m", "hi", 1024, null);

        // include_usage 与会话无关：不传它上游流式就不回 usage
        assertTrue(body.contains("\"include_usage\":true"), body);
        assertFalse(body.contains("prompt_cache_key"), body);
    }

    @Test
    void openAiProtocolKeepsTheBodyFreeOfVendorFields() {
        AiProtocol openAi = OpenAiProtocol.INSTANCE;
        String body = openAi.buildBody("glm-4-flash", "hi", 1024, "conversation-42");

        assertEquals("openai", openAi.name());
        assertFalse(body.contains("prompt_cache_key"), body);
        assertFalse(body.contains("include_usage"), body);
        assertTrue(openAi.perRequestHeaders("conversation-42").isEmpty(),
                "通用 OpenAI 端点不应收到任何每请求头");
        assertNull(openAi.perRequestHeaders(null).get("x-opencode-session"));
    }
}

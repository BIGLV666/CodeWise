package org.example.serviceai.controller;

import org.example.serviceai.service.AiProviderHttpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SSE 错误文案脱敏测试：兜底分支返回固定文案（不含 Provider 名/内部根因），
 * AiProviderHttpException 的友好分支保留。
 */
class AiAdviceControllerTest {

    private final AiAdviceController controller = new AiAdviceController();

    @Test
    void fallbackReturnsFixedMessageWithoutInternalDetails() {
        RuntimeException exception = new RuntimeException(
                "AI providers failed: first=glm-4, firstCause=SocketTimeoutException: "
                        + "read timeout https://internal-proxy.codewise.local/v1/chat");

        String message = controller.toUserMessage(exception);

        assertEquals("AI 服务暂时不可用，请稍后重试", message);
        assertFalse(message.contains("glm-4"));
        assertFalse(message.contains("internal-proxy"));
    }

    @Test
    void nullMessageFallsBackToFixedText() {
        String message = controller.toUserMessage(new RuntimeException((String) null));

        assertEquals("AI 服务暂时不可用，请稍后重试", message);
    }

    @Test
    void providerAuthFailureKeepsFriendlyBranch() {
        String message = controller.toUserMessage(
                new AiProviderHttpException("custom", 403, "secret upstream body", null));

        assertEquals("AI 服务认证失败，请检查 API Key 和模型权限", message);
    }

    @Test
    void providerRateLimitKeepsFriendlyBranch() {
        String message = controller.toUserMessage(
                new AiProviderHttpException("custom", 429, "quota exceeded", "30"));

        assertEquals("AI 服务请求过于频繁或额度不足，请稍后重试", message);
    }

    @Test
    void nestedProviderHttpExceptionIsDetected() {
        RuntimeException nested = new RuntimeException("wrapper",
                new AiProviderHttpException("custom", 500, "boom", null));

        String message = controller.toUserMessage(nested);

        assertEquals("AI 服务调用失败，HTTP 500", message);
    }
}

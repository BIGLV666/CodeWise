package org.example.serviceai.controller;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.service.AiProviderHttpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SSE 错误文案测试：文案一律来自错误码枚举（BizException 消息），
 * 非业务异常兜底返回固定中文提示，不含 Provider 名/内部根因/状态码数字。
 */
class AiAdviceControllerTest {

    private final AiAdviceController controller = new AiAdviceController();

    @Test
    void fallbackReturnsFixedMessageWithoutInternalDetails() {
        RuntimeException exception = new RuntimeException(
                "AI providers failed: first=glm-4, firstCause=SocketTimeoutException: "
                        + "read timeout https://internal-proxy.codewise.local/v1/chat");

        String message = controller.toUserMessage(exception);

        assertEquals(AiErrorCode.AI_SERVICE_NOT_READY.getMessage(), message);
        assertFalse(message.contains("glm-4"));
        assertFalse(message.contains("internal-proxy"));
    }

    @Test
    void nullMessageFallsBackToFixedText() {
        String message = controller.toUserMessage(new RuntimeException((String) null));

        assertEquals(AiErrorCode.AI_SERVICE_NOT_READY.getMessage(), message);
    }

    @Test
    void providerAuthFailureUsesKeyErrorCode() {
        String message = controller.toUserMessage(
                new AiProviderHttpException("custom", 403, "secret upstream body", null));

        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getMessage(), message);
        assertFalse(message.contains("403"));
    }

    @Test
    void providerRateLimitUsesQuotaErrorCode() {
        String message = controller.toUserMessage(
                new AiProviderHttpException("custom", 429, "quota exceeded", "30"));

        assertEquals(AiErrorCode.PROVIDER_QUOTA_EXCEEDED.getMessage(), message);
    }

    @Test
    void nestedProviderHttpExceptionIsDetected() {
        RuntimeException nested = new RuntimeException("wrapper",
                new AiProviderHttpException("custom", 500, "boom", null));

        String message = controller.toUserMessage(nested);

        assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getMessage(), message);
        assertFalse(message.contains("500"));
    }
}

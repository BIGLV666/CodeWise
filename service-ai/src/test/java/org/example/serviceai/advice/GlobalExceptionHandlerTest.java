package org.example.serviceai.advice;

import org.example.serviceai.service.AiProviderHttpException;
import org.example.serviceapi.dto.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsProviderAuthenticationFailureToSafeMessage() {
        Result<Void> result = handler.handleProviderException(
                new AiProviderHttpException("custom", 403, "secret upstream body", null)
        );

        assertEquals(400, result.getCode());
        assertEquals("AI 服务认证失败，请检查 API Key 和模型权限", result.getMessage());
    }

    @Test
    void mapsProviderRateLimitTo429() {
        Result<Void> result = handler.handleProviderException(
                new AiProviderHttpException("custom", 429, "quota exceeded", "30")
        );

        assertEquals(429, result.getCode());
        assertEquals("AI 服务请求过于频繁或额度不足，请稍后重试", result.getMessage());
    }
}

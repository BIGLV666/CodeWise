package org.example.serviceai.advice;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.service.AiProviderHttpException;
import org.example.serviceapi.dto.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsProviderAuthenticationFailureToKeyErrorCode() {
        Result<?> result = handler.handleProviderException(
                new AiProviderHttpException("custom", 403, "secret upstream body", null)
        );

        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getCode(), result.getCode());
        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getMessage(), result.getMessage());
        assertFalse(result.getMessage().contains("403"));
        assertFalse(result.getMessage().contains("secret"));
    }

    @Test
    void mapsProviderRateLimitToQuotaErrorCode() {
        Result<?> result = handler.handleProviderException(
                new AiProviderHttpException("custom", 429, "quota exceeded", "30")
        );

        assertEquals(AiErrorCode.PROVIDER_QUOTA_EXCEEDED.getCode(), result.getCode());
        assertEquals(AiErrorCode.PROVIDER_QUOTA_EXCEEDED.getMessage(), result.getMessage());
    }

    @Test
    void mapsOtherProviderFailureToCallFailed() {
        Result<?> result = handler.handleProviderException(
                new AiProviderHttpException("custom", 500, "boom", null)
        );

        assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getCode(), result.getCode());
        assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getMessage(), result.getMessage());
    }
}

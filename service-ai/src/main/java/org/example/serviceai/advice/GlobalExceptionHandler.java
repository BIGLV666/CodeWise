package org.example.serviceai.advice;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.service.AiProviderHttpException;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "org.example.serviceai.controller")
public class GlobalExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public Result<Void> handleSecurityException(SecurityException exception) {
        return Result.error(exception.getMessage(), 403);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException exception) {
        return Result.error(exception.getMessage(), 400);
    }

    @ExceptionHandler(AiProviderHttpException.class)
    public Result<Void> handleProviderException(AiProviderHttpException exception) {
        int statusCode = exception.getStatusCode();
        if (statusCode == 401 || statusCode == 403) {
            return Result.error("AI 服务认证失败，请检查 API Key 和模型权限", 400);
        }
        if (statusCode == 429) {
            return Result.error("AI 服务请求过于频繁或额度不足，请稍后重试", 429);
        }
        return Result.error("AI 服务调用失败，HTTP " + statusCode, 502);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        log.error("AI service request failed", exception);
        return Result.error("AI 服务内部异常，请稍后重试", 500);
    }
}

package org.example.serviceai.service;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.ErrorCode;

/**
 * AI 服务商 HTTP 调用被拒绝的异常：面向用户的消息固定取自 {@link AiErrorCode}
 * （401/403 → 密钥或权限无效，429 → 额度或频率不足，其余 → 调用失败），
 * 上游原始状态码与响应体只作为 cause/diagnostic 进服务端日志，绝不透传前端。
 *
 * <p>SSE 与同步链路按 {@link #getStatusCode()} 区分失败类别，HTTP 状态码语义
 * 保留在字段上而非消息文本里。</p>
 */
public class AiProviderHttpException extends BizException {

    private final int statusCode;
    private final String retryAfter;

    public AiProviderHttpException(
            String provider,
            int statusCode,
            String responseBody,
            String retryAfter
    ) {
        super(selectErrorCode(statusCode),
                new IllegalStateException(buildDiagnostic(provider, statusCode, responseBody, retryAfter)));
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    /** 仅用于日志排查的诊断信息（含服务商名、状态码与上游响应体）。 */
    public String getDiagnostic() {
        Throwable cause = getCause();
        return cause == null ? "" : String.valueOf(cause.getMessage());
    }

    private static ErrorCode selectErrorCode(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return AiErrorCode.PROVIDER_KEY_INVALID;
        }
        if (statusCode == 429) {
            return AiErrorCode.PROVIDER_QUOTA_EXCEEDED;
        }
        return AiErrorCode.PROVIDER_CALL_FAILED;
    }

    private static String buildDiagnostic(
            String provider,
            int statusCode,
            String responseBody,
            String retryAfter
    ) {
        StringBuilder message = new StringBuilder(provider)
                .append(" returned HTTP ")
                .append(statusCode);
        if (retryAfter != null && !retryAfter.isBlank()) {
            message.append(", retryAfter=").append(retryAfter);
        }
        if (responseBody != null && !responseBody.isBlank()) {
            message.append(", body=").append(responseBody);
        }
        return message.toString();
    }
}

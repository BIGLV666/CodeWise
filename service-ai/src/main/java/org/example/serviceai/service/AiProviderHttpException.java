package org.example.serviceai.service;

public class AiProviderHttpException extends RuntimeException {

    private final int statusCode;
    private final String retryAfter;

    public AiProviderHttpException(
            String provider,
            int statusCode,
            String responseBody,
            String retryAfter
    ) {
        super(buildMessage(provider, statusCode, responseBody, retryAfter));
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    private static String buildMessage(
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

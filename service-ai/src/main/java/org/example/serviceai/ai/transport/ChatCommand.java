package org.example.serviceai.ai.transport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次出站 AI 请求的最小描述：Provider 把业务参数翻译成它，Transport 只负责原样下发。
 *
 * <p>{@link #headers()} 由 Provider 自己组装，Transport 不做任何认证语义假设——
 * OpenAI 兼容端点填 {@code Authorization: Bearer}，订阅类端点填各自专属头
 * （例如 OpenCode 订阅的 {@code x-opencode-session}）。新增一种鉴权方式只需在
 * Provider 侧组装不同的头，Transport 一行不用改。</p>
 */
public record ChatCommand(
        String providerName,
        String endpointUrl,
        Map<String, String> headers,
        String jsonBody,
        Duration connectTimeout,
        Duration readIdleTimeout
) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_IDLE_TIMEOUT = Duration.ofSeconds(120);

    public ChatCommand {
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        jsonBody = jsonBody == null ? "" : jsonBody;
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        readIdleTimeout = readIdleTimeout == null ? DEFAULT_READ_IDLE_TIMEOUT : readIdleTimeout;
    }

    public static Builder builder(String providerName, String endpointUrl) {
        return new Builder(providerName, endpointUrl);
    }

    public static final class Builder {

        private final String providerName;
        private final String endpointUrl;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String jsonBody = "";
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration readIdleTimeout = DEFAULT_READ_IDLE_TIMEOUT;

        private Builder(String providerName, String endpointUrl) {
            this.providerName = providerName;
            this.endpointUrl = endpointUrl;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> values) {
            if (values != null) {
                values.forEach(this::header);
            }
            return this;
        }

        public Builder jsonBody(String body) {
            this.jsonBody = body;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            if (timeout != null) {
                this.connectTimeout = timeout;
            }
            return this;
        }

        public Builder readIdleTimeout(Duration timeout) {
            if (timeout != null) {
                this.readIdleTimeout = timeout;
            }
            return this;
        }

        public ChatCommand build() {
            return new ChatCommand(
                    providerName,
                    endpointUrl,
                    headers,
                    jsonBody,
                    connectTimeout,
                    readIdleTimeout
            );
        }
    }
}

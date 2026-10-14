package org.example.serviceai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.util.GetAPiKey;
import org.example.serviceapi.exception.BizException;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 由 {@code ai.models} 配置驱动的通用 AI 端点实现：一个 {@link Model} 对应一个实例，
 * 统一走 OpenAI 兼容的 chat/completions SSE 协议。
 *
 * <p>本类刻意不是 Spring Bean——{@link Model} 是配置数据而非 Bean，无法注入。它由
 * AIServiceManager 在装配阶段按配置逐条 new 出来；所依赖的 {@link GetAPiKey} 先由容器
 * 注入工厂，再由工厂传入构造器，因此依赖来源仍是 IoC 容器。</p>
 */
@Slf4j
public class AiAggregateService implements CallAi {

    private final Model config;
    private final GetAPiKey getAPiKey;

    public AiAggregateService(Model config, GetAPiKey getAPiKey) {
        this.config = config;
        this.getAPiKey = getAPiKey;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public String getModelName() {
        return config.getDefaultModelName();
    }

    @Override
    public String callAi(String prompt) {
        return callAi(prompt, 1024);
    }

    @Override
    public String callAi(String prompt, String modelName) {
        StringBuilder result = new StringBuilder();
        streamAi(prompt, 1024, result::append, modelName);
        return result.toString();
    }

    @Override
    public String callAi(String prompt, int maxTokens) {
        StringBuilder result = new StringBuilder();
        streamAi(prompt, maxTokens, result::append);
        return result.toString();
    }

    @Override
    public void streamAi(String prompt, Consumer<String> onChunk) {
        streamAi(prompt, 1024, onChunk);
    }

    @Override
    public void streamAi(String prompt, Consumer<String> onChunk, String modelName) {
        streamAi(prompt, 1024, onChunk, modelName);
    }

    private void streamAi(String prompt, int maxTokens, Consumer<String> onChunk) {
        streamAi(prompt, maxTokens, onChunk, config.getDefaultModelName());
    }

    private void streamAi(String prompt, int maxTokens, Consumer<String> onChunk, String model) {
        String provider = providerName();
        HttpURLConnection connection = null;
        try {
            JSONObject body = requestBody(prompt, maxTokens, model);
            log.info("{} API request, model={}, promptLength={}",
                    provider, model, prompt == null ? 0 : prompt.length());

            connection = openConnection(config.getUrl(), getAPiKey.getApikey(config));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseLength = readStream(connection, provider, onChunk);
            log.info("{} API stream completed, responseLength={}", provider, responseLength);
        } catch (AiProviderHttpException exception) {
            log.warn("{} API request rejected: {}", provider, exception.getDiagnostic());
            throw exception;
        } catch (Exception exception) {
            log.error("{} API stream failed: {}", provider, exception.getMessage(), exception);
            throw new BizException(AiErrorCode.PROVIDER_CALL_FAILED, exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject requestBody(String prompt, int maxTokens, String modelName) {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", 0.7);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        body.put("messages", messages);
        return body;
    }

    private HttpURLConnection openConnection(String endpoint, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoOutput(true);
        connection.setConnectTimeout(getTimeout());
        connection.setReadTimeout(getTimeout());
        return connection;
    }

    private int readStream(
            HttpURLConnection connection,
            String provider,
            Consumer<String> onChunk
    ) throws Exception {
        int responseLength = 0;
        try (BufferedReader reader = AiHttpSupport.responseReader(connection, provider)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String content = parseContent(line, provider);
                if (content != null) {
                    responseLength += content.length();
                    onChunk.accept(content);
                }
            }
        }
        return responseLength;
    }

    private String parseContent(String line, String provider) {
        // SSE 规范允许 "data:" 后不带空格，部分网关（如 new-api）会省略——只认 "data: " 会静默丢块导致回复截断
        if (!line.startsWith("data:")) {
            return null;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JSONObject chunk = new JSONObject(data);
            JSONArray choices = chunk.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
            return delta == null ? null : delta.getStr("content");
        } catch (Exception exception) {
            log.warn("{} stream chunk parse failed: {}", provider, exception.getMessage());
            return null;
        }
    }

    /** 日志/异常里用的端点标识：优先服务名，未配置时退回默认模型名，避免打印 null。 */
    private String providerName() {
        return config.getName() != null && !config.getName().isBlank()
                ? config.getName()
                : config.getDefaultModelName();
    }

    @Override
    public int getPriority() {
        return config.getPriority() == null ? 0 : config.getPriority();
    }

    @Override
    public int getTimeout() {
        return config.getTimeout();
    }
}

package org.example.serviceai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.config.NikoApiGpt;
import org.example.serviceai.intifer.CallAi;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Slf4j
@Service
public class NikoApiGptService implements CallAi {

    private final NikoApiGpt config;

    public NikoApiGptService(NikoApiGpt config) {
        this.config = config;
    }

    @Override
    public String getModelName() {
        return "GPT";
    }

    @Override
    public String callAi(String prompt) {
        return callAi(prompt, 1024);
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

    private void streamAi(String prompt, int maxTokens, Consumer<String> onChunk) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = requestBody(prompt, maxTokens);
            log.info("Niko API request, model={}, promptLength={}",
                    config.getModel(), prompt == null ? 0 : prompt.length());

            connection = openConnection(config.getEndpoint(), config.getApikey());
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseLength = readStream(connection, "Niko API", onChunk);
            log.info("Niko API stream completed, responseLength={}", responseLength);
        } catch (AiProviderHttpException exception) {
            log.warn("Niko API request rejected: {}", exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.error("Niko API stream failed: {}", exception.getMessage(), exception);
            throw new RuntimeException("Niko API 流式调用失败", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject requestBody(String prompt, int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", config.getModel());
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
        if (!line.startsWith("data: ")) {
            return null;
        }
        String data = line.substring(6).trim();
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

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public int getTimeout() {
        return 120000;
    }
}

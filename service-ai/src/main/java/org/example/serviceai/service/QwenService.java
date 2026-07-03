package org.example.serviceai.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.config.QwenConfig;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QwenService implements CallAi {

    @Autowired
    private QwenConfig qwenConfig;

    @Override
    public String getModelName() {
        return "QWEN";
    }

    @Override
    public int getPriority() {
        return 2;  // 优先级低，GLM 优先
    }

    @Override
    public int getTimeout() {
        return 120000;
    }

    @Override
    public String callAi(String prompt) {
        // 🔥 直接用非流式，只改这一个方法
        return callAiNonStream(prompt);
    }

    /**
     * 非流式调用 - 稳定可靠
     */
    private String callAiNonStream(String prompt) {
        log.info("🔵 Qwen 调用，prompt长度: {}", prompt.length());

        try {
            // 构建请求
            JSONObject body = new JSONObject();
            body.put("model", qwenConfig.getModel());

            JSONObject parameters = new JSONObject();
            parameters.put("result_format", "message");
            parameters.put("stream", false);  // 🔥 关键：非流式
            parameters.put("max_tokens", 16384);
            parameters.put("temperature", 0.7);
            body.put("parameters", parameters);

            JSONObject input = new JSONObject();
            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            input.put("messages", messages);
            body.put("input", input);

            String requestBody = body.toString();

            // 发送请求
            String response = HttpUtil.createPost("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation")
                    .header("Authorization", "Bearer " + qwenConfig.getApikey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .timeout(18000)
                    .execute()
                    .body();

            log.info("📊 响应长度: {}", response != null ? response.length() : 0);

            // 提取内容
            String content = extractContent(response);

            // 🔥 如果提取的内容是空，尝试从原始响应中直接截取 JSON
            if (content == null || content.isEmpty()) {
                content = extractJsonFromResponse(response);
            }

            log.info("📝 最终内容长度: {}", content != null ? content.length() : 0);
            if (content != null && content.length() > 0) {
                log.info("📝 内容前200字符: {}", content.substring(0, Math.min(200, content.length())));
            }

            return content != null ? content : "";

        } catch (Exception e) {
            log.error("❌ Qwen 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("Qwen 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取响应内容
     */
    public String extractContent(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            JSONObject root = new JSONObject(response);

            if (root.containsKey("error")) {
                JSONObject error = root.getJSONObject("error");
                log.error("API返回错误: {}", error);
                return null;
            }

            JSONObject output = root.getJSONObject("output");
            if (output == null) {
                return null;
            }

            JSONArray choices = output.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");
                if (message != null) {
                    return message.getStr("content");
                }
            }

            return output.getStr("text");

        } catch (Exception e) {
            log.error("提取内容失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 🔥 直接从原始响应中截取 JSON（备用方案）
     * 处理 Qwen 返回内容中可能包含的 markdown 标记
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        String content = response;

        // 移除所有 ```json 和 ``` 标记
        content = content.replaceAll("```json\\s*", "");
        content = content.replaceAll("```\\s*", "");
        content = content.replaceAll("`", "");
        content = content.trim();

        // 找到第一个 { 和最后一个 }
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');

        if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            return content.substring(firstBrace, lastBrace + 1);
        }

        return content;
    }
}
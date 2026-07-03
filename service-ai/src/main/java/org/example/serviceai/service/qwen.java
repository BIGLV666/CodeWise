package org.example.serviceai.service;

import cn.hutool.http.Header;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.config.QwenConfig;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Slf4j

public class qwen implements CallAi {

   // @Autowired
    private QwenConfig qwenConfig;

   // @Override
    public String getModelName() {
        return "QWEN";
    }

    //@Override
    public int getPriority() {
        return 1;
    }

   // @Override
    public int getTimeout() {
        return 120000;
    }

   // @Override
    public String callAi(String prompt) {
        return callAiWithRetry(prompt, 3);
    }

    /**
     * 带重试的调用
     */
    public String callAiWithRetry(String prompt, int maxRetries) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("========== 第 {} 次调用 Qwen API（流式）==========", attempt);
                log.info("📝 Prompt 长度: {}", prompt != null ? prompt.length() : 0);

                String response = callStreamOnce(prompt);

                if (response != null && !response.isEmpty()) {
                    log.info("✅ 第 {} 次调用成功，响应长度: {}", attempt, response.length());
                    return response;
                } else {
                    log.warn("⚠️ 第 {} 次调用返回空响应", attempt);
                    if (attempt < maxRetries) {
                        Thread.sleep(3000L * attempt);
                    }
                }

            } catch (Exception e) {
                lastException = e;
                log.error("❌ 第 {} 次调用失败: {}", attempt, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(3000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        throw new RuntimeException("Qwen API调用失败，已重试 " + maxRetries + " 次", lastException);
    }

    /**
     * 单次流式调用
     */
    private String callStreamOnce(String prompt) throws Exception {
        log.info("==================== Qwen 调用开始 ====================");
        log.info("📝 Prompt 前200字符: {}", prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);

        // ========== 第一步：构建请求体 ==========
        log.info("📦 构建请求体...");

        JSONObject body = new JSONObject();
        body.put("model", qwenConfig.getModel());

        JSONObject parameters = new JSONObject();
        parameters.put("result_format", "message");
        parameters.put("stream", true);
        parameters.put("max_tokens", 16000);
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
        log.info("📦 请求体长度: {}", requestBody.length());

        // ========== 第二步：发送请求 ==========
        log.info("🚀 发送请求到 Qwen API...");
        log.info("   - URL: https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
        log.info("   - Authorization: Bearer {}...", qwenConfig.getApikey() != null && qwenConfig.getApikey().length() > 10 ?
                qwenConfig.getApikey().substring(0, 10) : "null");

        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            URL url = new URL("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + qwenConfig.getApikey());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(120000);
            connection.setReadTimeout(120000);

            // 写入请求体
            connection.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
            connection.getOutputStream().close();

            // ========== 第三步：检查响应码 ==========
            int responseCode = connection.getResponseCode();
            log.info("📊 响应码: {}", responseCode);

            if (responseCode != 200) {
                // 读取错误信息
                BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8)
                );
                StringBuilder errorResponse = new StringBuilder();
                String errorLine;
                while ((errorLine = errorReader.readLine()) != null) {
                    errorResponse.append(errorLine);
                }
                errorReader.close();
                log.error("❌ API返回错误: code={}, body={}", responseCode, errorResponse);
                throw new RuntimeException("API调用失败: " + responseCode + ", 错误: " + errorResponse);
            }

            // ========== 第四步：读取流式响应 ==========
            log.info("📖 开始读取流式响应...");

            reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );

            StringBuilder fullResponse = new StringBuilder();
            String line;
            int chunkCount = 0;
            int validChunkCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();

                    if ("[DONE]".equals(data)) {
                        log.info("✅ 流式响应结束，共 {} 个数据块", chunkCount);
                        break;
                    }

                    if (!data.isEmpty()) {
                        chunkCount++;
                        try {
                            JSONObject chunk = new JSONObject(data);

                            // 检查错误
                            if (chunk.containsKey("error")) {
                                JSONObject error = chunk.getJSONObject("error");
                                log.error("❌ 流式错误: {}", error);
                                throw new RuntimeException("API错误: " + error.getStr("message"));
                            }

                            // 提取内容 - result_format: message
                            String content = extractStreamContent(chunk);
                            if (content != null && !content.isEmpty()) {
                                fullResponse.append(content);
                                validChunkCount++;

                                if (validChunkCount % 50 == 0) {
                                    log.info("📊 已接收 {} 个有效数据块，当前长度: {}", validChunkCount, fullResponse.length());
                                }
                            }

                        } catch (Exception e) {
                            log.warn("⚠️ 解析流式数据失败: {}", e.getMessage());
                            log.warn("⚠️ 原始数据: {}", data.substring(0, Math.min(200, data.length())));
                        }
                    }
                }
            }

            // ========== 第五步：输出结果 ==========
            String result = fullResponse.toString();

            log.info("==================== Qwen 调用完成 ====================");
            log.info("✅ 总数据块数: {}", chunkCount);
            log.info("✅ 有效内容块: {}", validChunkCount);
            log.info("✅ 响应总长度: {}", result.length());

            if (result.length() > 0) {
                log.info("✅ 响应前500字符: {}", result.length() > 500 ? result.substring(0, 500) + "..." : result);
                log.info("✅ 响应后200字符: {}", result.length() > 200 ? "..." + result.substring(result.length() - 200) : result);
            } else {
                log.warn("⚠️ 响应内容为空！请检查API配置");
            }

            log.info("========================================================");

            return result;

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    log.warn("关闭 reader 失败: {}", e.getMessage());
                }
            }
            if (connection != null) {
                connection.disconnect();
                log.info("🔌 连接已断开");
            }
        }
    }

    /**
     * 从流式数据块中提取内容
     * 支持多种格式：
     * 1. output.choices[0].message.content (Qwen 流式格式)
     * 2. output.choices[0].delta.content (标准流式格式)
     * 3. output.text (非流式格式)
     */
    private String extractStreamContent(JSONObject chunk) {
        try {
            JSONObject output = chunk.getJSONObject("output");
            if (output == null) {
                return null;
            }

            JSONArray choices = output.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);

                // 方式1：从 message.content 获取（Qwen 流式格式）
                JSONObject message = choice.getJSONObject("message");
                if (message != null) {
                    String content = message.getStr("content");
                    if (content != null && !content.isEmpty()) {
                        return content;
                    }
                }

                // 方式2：从 delta.content 获取（标准流式格式）
                JSONObject delta = choice.getJSONObject("delta");
                if (delta != null) {
                    String content = delta.getStr("content");
                    if (content != null && !content.isEmpty()) {
                        return content;
                    }
                }
            }

            // 方式3：从 output.text 获取（非流式格式）
            String text = output.getStr("text");
            if (text != null && !text.isEmpty()) {
                return text;
            }

            return null;

        } catch (Exception e) {
            log.debug("提取内容失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取完整响应内容（非流式，备用）
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

            // 尝试从 choices 获取
            JSONArray choices = output.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");
                if (message != null) {
                    return message.getStr("content");
                }
            }

            // 尝试从 text 获取
            return output.getStr("text");

        } catch (Exception e) {
            log.error("提取内容失败: {}", e.getMessage());
            return response;
        }
    }
}
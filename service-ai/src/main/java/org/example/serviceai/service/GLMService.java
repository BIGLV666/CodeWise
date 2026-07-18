package org.example.serviceai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.config.GLMConfig;
import org.example.serviceai.intifer.CallAi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

@Slf4j
@Service
public class GLMService implements CallAi {

    @Autowired
    private GLMConfig glmConfig;
    @Override
    public String getModelName(){
        return "GLM";
    }
    @Override
    public String callAi(String prompt){
        StringBuilder result = new StringBuilder();

        streamAi(prompt, result::append);

        return result.toString();
    }

    @Override
    public void streamAi(String prompt, Consumer<String> onChunk)

     {
        try {
            JSONObject body = new JSONObject();
            body.put("model", glmConfig.getModel());
            body.put("temperature", 0.7);
            body.put("max_tokens", 8192);
            body.put("stream", true);  // 启用流式

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            body.put("messages", messages);
            log.info("GLM 请求体: {}", body.toString());

            URL url = new URL("https://open.bigmodel.cn/api/paas/v4/chat/completions");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + glmConfig.getApikey());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(120000);
            connection.setReadTimeout(120000);

            connection.getOutputStream().write(body.toString().getBytes());
            connection.getOutputStream().flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8")
            );

            StringBuilder fullResponse = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if (!"[DONE]".equals(data.trim())) {
                        try {
                            JSONObject chunk = new JSONObject(data);
                            JSONArray choices = chunk.getJSONArray("choices");
                            if (choices != null && !choices.isEmpty()) {
                                JSONObject choice = choices.getJSONObject(0);
                                JSONObject delta = choice.getJSONObject("delta");
                                String content = delta.getStr("content");
                                if (content != null) {
                                    onChunk.accept(content);
                                    // 实时打印进度
                                    if (fullResponse.length() % 100 == 0) {
                                        log.info("已接收 {} 字符", fullResponse.length());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("解析流式数据失败: {}", e.getMessage());
                        }
                    }
                }
            }

            reader.close();
            connection.disconnect();

            log.info("流式响应总长度: {}", fullResponse.length());

        } catch (Exception e) {
            log.error("流式调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("GLM流式调用失败", e);
        }
    }



    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public int getTimeout() {
        return 120000;
    }
}

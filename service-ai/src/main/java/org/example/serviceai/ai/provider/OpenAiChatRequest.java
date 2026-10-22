package org.example.serviceai.ai.provider;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

/**
 * OpenAI 兼容的 {@code chat/completions} 请求体。
 *
 * <p>统一发 {@code stream:true}：非流式接口由 Transport 在流式之上聚合，
 * 两条路径共用一份线上协议，避免再出现「同步与流式各写一份、行为不一致」。</p>
 *
 * <p>这里只有<b>通用</b>字段。厂商私有字段一律不放——不认识它们的上游可能直接报错，
 * 需要私有字段的协议请在自己的类里基于 {@link #baseBody} 追加（见
 * {@link OpenCodeChatRequest}）。</p>
 */
public final class OpenAiChatRequest {

    private static final String ROLE_USER = "user";

    private OpenAiChatRequest() {
    }

    public static String body(String model, String prompt, int maxTokens) {
        return baseBody(model, prompt, maxTokens).toString();
    }

    /**
     * 通用体的公共部分（模型、温度、token 上限、流式开关、单条 user 消息）。
     *
     * <p>供各协议在自己的请求体类里复用，避免把「温度 0.7」「stream:true」这些约定
     * 在每个协议里各抄一遍、日后改不齐。</p>
     */
    static JSONObject baseBody(String model, String prompt, int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);

        JSONArray messages = new JSONArray();
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", ROLE_USER);
        userMessage.put("content", prompt);
        messages.add(userMessage);
        body.put("messages", messages);
        return body;
    }
}

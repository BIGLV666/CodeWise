package org.example.serviceai.ai.provider;

import cn.hutool.json.JSONObject;

/**
 * OpenCode Go 的请求体：在通用 OpenAI 兼容体之上多两个字段。
 *
 * <ul>
 *   <li>{@code prompt_cache_key} —— 官方分组键（"把一组相关请求绑在一起"），上游与前置网关
 *       都认它，取与请求头里相同的会话 id。</li>
 *   <li>{@code stream_options.include_usage = true} —— 不带它上游在流式响应里不回 usage
 *       （usage 落在末尾那个 {@code choices} 为空数组的分片里）。OpenCode Go 已确认支持该参数，
 *       所以这里固定打开；其它上游支持情况不一，因此这个字段只出现在本协议里。</li>
 * </ul>
 */
public final class OpenCodeChatRequest {

    private OpenCodeChatRequest() {
    }

    /**
     * @param sessionId 已经规整成 {@code ses_} 形状的会话 id；为空时不下发 {@code prompt_cache_key}
     */
    public static String body(String model, String prompt, int maxTokens, String sessionId) {
        JSONObject body = OpenAiChatRequest.baseBody(model, prompt, maxTokens);

        if (sessionId != null && !sessionId.isBlank()) {
            body.put("prompt_cache_key", sessionId);
        }
        JSONObject streamOptions = new JSONObject();
        streamOptions.put("include_usage", true);
        body.put("stream_options", streamOptions);
        return body.toString();
    }
}

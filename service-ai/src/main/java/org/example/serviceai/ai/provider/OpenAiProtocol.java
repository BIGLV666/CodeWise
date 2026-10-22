package org.example.serviceai.ai.provider;

/**
 * 标准 OpenAI 兼容协议的差异点：请求体就是通用体，除鉴权头外不额外带任何头。
 *
 * <p>刻意<b>不</b>往请求体里塞任何厂商私有字段：不认识它们的上游可能直接报错。
 * 需要私有字段的协议（如 OpenCode Go）请另建实现。</p>
 */
public final class OpenAiProtocol implements AiProtocol {

    public static final OpenAiProtocol INSTANCE = new OpenAiProtocol();

    private OpenAiProtocol() {
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public String buildBody(String model, String prompt, int maxTokens, String sessionRef) {
        return OpenAiChatRequest.body(model, prompt, maxTokens);
    }
}

package org.example.serviceai.ai.provider;

import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.entry.Model;
import org.springframework.stereotype.Component;

/**
 * {@code type: openai}（也是缺省值）：标准 OpenAI 兼容端点。
 *
 * <p>凭证走 {@code Authorization: Bearer <key>}（key 从该端点的 key 池取），请求体只发通用
 * OpenAI 字段，不额外带任何每请求头。</p>
 */
@Component
public class OpenAiProviderCreator implements AiProviderCreator {

    public static final String TYPE = "openai";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public AiProvider create(Model model, AiKeyPool keyPool, AiTransport transport) {
        return new OpenAiCompatibleProvider(
                AiProviderCreators.specOf(model, OpenAiProtocol.INSTANCE), keyPool, transport);
    }
}

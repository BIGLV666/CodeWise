package org.example.serviceai.ai.provider;

import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.entry.Model;
import org.springframework.stereotype.Component;

/**
 * {@code type: opencode-go}：OpenCode Go 端点。
 *
 * <p>与 openai 类型的差别只有协议本身（每次请求带会话头、请求体多两个字段），鉴权仍是
 * {@code Authorization: Bearer}，所以复用同一个 Provider，只换 {@link AiProtocol}。</p>
 *
 * <p><b>用之前需要知道的两件事：</b></p>
 * <ol>
 *   <li>上游要求 {@code x-opencode-session}，它由 {@code AiRequest.sessionRef()} 派生。
 *       走这条端点的调用方必须传会话标识（会话主键即可），否则上游返回 400——
 *       见 {@link OpenCodeProtocol}。</li>
 *   <li>该端点<b>不适合放进 AUTO 轮转</b>：AUTO 的调用方（测试用例生成、函数产物等）手里
 *       没有会话概念，传不下会话标识就会 400。要用请只用「指定模型」这条路。</li>
 * </ol>
 */
@Component
public class OpenCodeGoProviderCreator implements AiProviderCreator {

    public static final String TYPE = "opencode-go";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public AiProvider create(Model model, AiKeyPool keyPool, AiTransport transport) {
        return new OpenAiCompatibleProvider(
                AiProviderCreators.specOf(model, OpenCodeProtocol.INSTANCE), keyPool, transport);
    }
}

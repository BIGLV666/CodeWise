package org.example.serviceai.ai.provider;

import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.entry.Model;

/**
 * 按 {@code ai.models[].type} 创建 Provider 的扩展点。
 *
 * <p>接入一种新的 AI 提供方式＝新增一个实现并注册为 Bean，{@code AiServiceFactory}
 * 用 {@code type()} 建索引、不需要任何改动。已实现：{@link OpenAiProviderCreator}（openai）、
 * {@link SubscriptionProviderCreator}（subscription，走订阅专属鉴权头）。</p>
 */
public interface AiProviderCreator {

    /** 与 {@code ai.models[].type} 的取值对应。 */
    String type();

    /**
     * 把一条配置翻译成一个可调用的 Provider。
     *
     * <p>实现方只需组装参数，HTTP 与鉴权下发分别交给 {@link AiTransport} 与
     * {@link ChatCommand}；装配期抛异常会被 Factory 记录并跳过该条配置，不影响启动。</p>
     */
    AiProvider create(Model model, org.example.serviceai.ai.key.AiKeyPool keyPool, AiTransport transport);
}

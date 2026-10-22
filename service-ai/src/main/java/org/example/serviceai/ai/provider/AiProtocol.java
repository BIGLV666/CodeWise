package org.example.serviceai.ai.provider;

import java.util.Map;

/**
 * 一种接入协议的差异点合集：请求体怎么组、除鉴权头外还要带哪些「每请求」头。
 *
 * <p>OpenAI 兼容协议与 OpenCode Go 的差别全部收敛在这里，Provider 本身保持协议无关——
 * 新增一种接入方式＝新增一个 {@link AiProtocol} + 一个 {@link AiProviderCreator}，
 * Provider、传输层与业务层都不用动。</p>
 */
public interface AiProtocol {

    /** 协议名，用于日志与排查。 */
    String name();

    /**
     * 组装请求体。
     *
     * @param sessionRef 会话标识（稳定、每会话一个，见 {@link AiRequest#sessionRef()}）；协议不需要时为 null
     */
    String buildBody(String model, String prompt, int maxTokens, String sessionRef);

    /**
     * 除鉴权头外、需要按<b>每次请求</b>下发的头。
     *
     * <p>只放「每请求才确定」的头（例如 OpenCode Go 的会话头）；静态头走
     * {@code ai.models[].headers} 配置，不要写在这里。</p>
     */
    default Map<String, String> perRequestHeaders(String sessionRef) {
        return Map.of();
    }
}

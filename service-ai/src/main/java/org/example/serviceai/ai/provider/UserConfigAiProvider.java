package org.example.serviceai.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.ai.transport.ChatCommand;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 用户自带配置的端点（{@code user_ai_config} 行）：一次性 Provider，不进 AI 池。
 *
 * <p>为什么不做池成员：池的前提是「同质、可互换、预注册」，而用户端点的地址、key、模型
 * 每个请求都可能不同，按 {@code (userId, configId)} 塞进池会退化成无界增长的缓存。
 * 它复用的是真正需要复用的东西——{@link AiTransport} 里的连接池。</p>
 *
 * <p>地址合法性、归属校验、模型是否属于该配置，都在构建本类之前由
 * {@link org.example.serviceai.ai.config.UserAiConfigService} 完成（含内网地址拦截），
 * 本类只管发请求。</p>
 */
@Slf4j
public class UserConfigAiProvider implements AiProvider {

    private static final String LOG_NAME = "自定义 AI";

    private final Long configId;
    private final String endpointUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final AiTransport transport;

    public UserConfigAiProvider(
            Long configId,
            String endpointUrl,
            String apiKey,
            String model,
            Duration timeout,
            AiTransport transport
    ) {
        this.configId = configId;
        this.endpointUrl = endpointUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.transport = transport;
    }

    /** 用户端点的身份就是配置行 + 模型；它不参与轮转，所以键不会与平台端点冲突。 */
    @Override
    public String name() {
        return "user-config-" + configId + ":" + model;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public String chat(AiRequest request) {
        return transport.send(command(request));
    }

    @Override
    public void chatStream(AiRequest request, Consumer<String> onChunk) {
        transport.sendStream(command(request), onChunk);
    }

    private ChatCommand command(AiRequest request) {
        String prompt = request.prompt();
        log.info("Custom AI request, configId={}, model={}, promptLength={}",
                configId, model, prompt == null ? 0 : prompt.length());
        return ChatCommand.builder(LOG_NAME, endpointUrl)
                .header(AuthScheme.bearer().headerName(), AuthScheme.bearer().format(apiKey))
                .header("Accept", "text/event-stream")
                // 用户自带端点一律按标准 OpenAI 兼容协议发，不塞任何厂商私有字段
                .jsonBody(OpenAiChatRequest.body(model, prompt, request.maxTokens()))
                .connectTimeout(timeout)
                .readIdleTimeout(timeout)
                .build();
    }
}

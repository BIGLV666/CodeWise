package org.example.serviceai.ai.provider;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.ai.transport.ChatCommand;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 由 {@code ai.models} 配置驱动的端点实现：一条配置换一个 {@link AiEndpointSpec}，
 * 协议差异交给 {@link AiProtocol}，本类保持协议无关。
 *
 * <p>本类刻意不是 Spring Bean——配置数据不是 Bean，无法注入。它由
 * {@link org.example.serviceai.ai.pool.AiServiceFactory} 逐条创建，依赖的
 * {@link AiKeyPool}、{@link AiTransport} 由容器注入工厂后传入。</p>
 *
 * <p>HTTP 细节全在 {@link AiTransport}，本类只负责把 {@link AiRequest} 翻译成
 * {@link ChatCommand}：鉴权头来自 {@link AiEndpointSpec#authScheme()}，
 * 每请求头与请求体来自 {@link AiEndpointSpec#protocol()}。</p>
 */
@Slf4j
public class OpenAiCompatibleProvider implements AiProvider {

    private final AiEndpointSpec spec;
    private final AiKeyPool keyPool;
    private final AiTransport transport;

    public OpenAiCompatibleProvider(AiEndpointSpec spec, AiKeyPool keyPool, AiTransport transport) {
        this.spec = spec;
        this.keyPool = keyPool;
        this.transport = transport;
    }

    /** 端点身份（日志、健康记账、key 池注册、按身份借用共用这一个值）。 */
    @Override
    public String name() {
        return spec.identity();
    }

    @Override
    public Set<String> servedModels() {
        return spec.servedModels();
    }

    @Override
    public boolean enabled() {
        return spec.enabled();
    }

    @Override
    public int priority() {
        return spec.priority();
    }

    @Override
    public Duration timeout() {
        return spec.timeout();
    }

    @Override
    public String chat(AiRequest request) {
        return transport.send(command(request));
    }

    @Override
    public void chatStream(AiRequest request, Consumer<String> onChunk) {
        AtomicInteger responseLength = new AtomicInteger();
        transport.sendStream(command(request), chunk -> {
            responseLength.addAndGet(chunk.length());
            onChunk.accept(chunk);
        });
        log.info("{} API stream completed, responseLength={}", name(), responseLength.get());
    }

    private ChatCommand command(AiRequest request) {
        // 请求里给了模型就用它（指定模型/用户配置路径），否则用端点默认模型（AUTO 路径）
        String model = request.modelName() == null || request.modelName().isBlank()
                ? spec.defaultModel()
                : request.modelName();
        log.info("{} API request, model={}, promptLength={}, protocol={}",
                name(), model, request.prompt() == null ? 0 : request.prompt().length(),
                spec.protocol().name());

        // 每次调用重新取 key：key 池的轮换语义在池里，不在这里
        String credential = keyPool.nextKey(name());
        return ChatCommand.builder(name(), spec.endpointUrl())
                .header(spec.authScheme().headerName(), spec.authScheme().format(credential))
                .header("Accept", "text/event-stream")
                .headers(spec.extraHeaders())
                // 每请求头放在静态头之后：同名时应以每请求的为准
                .headers(spec.protocol().perRequestHeaders(request.sessionRef()))
                .jsonBody(spec.protocol().buildBody(
                        model, request.prompt(), request.maxTokens(), request.sessionRef()))
                .connectTimeout(spec.timeout())
                .readIdleTimeout(spec.timeout())
                .build();
    }
}

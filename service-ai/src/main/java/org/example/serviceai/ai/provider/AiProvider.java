package org.example.serviceai.ai.provider;

import org.example.serviceai.ai.AiRequest;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 端点能力 SPI：一个实例代表一个可调用的平台端点。
 *
 * <p>入参是完整的 {@link AiRequest} 而不是 {@code (prompt, maxTokens, modelOverride)} 这样的
 * 基本类型包——后者每多一个「每请求才确定」的事实就要改一次 SPI 和所有实现，
 * 而协议侧真正需要的恰恰是这类事实（会话 id、模型覆盖）。</p>
 */
public interface AiProvider {

    String PROBE_PROMPT = "Reply with OK.";

    /** 端点身份，健康记账、key 池注册与按身份借用都用它。 */
    String name();

    /**
     * 本端点对外声明提供哪些模型，用于建「模型 → 端点」路由索引。
     *
     * <p>默认空集合：容器内的端点 Bean（如 ollama）不参与面向用户的模型路由，
     * 也不该被用户的模型选择命中。</p>
     */
    default Set<String> servedModels() {
        return Set.of();
    }

    /**
     * 是否参与轮转与健康探测。
     *
     * <p>只控制「是否参与」，不控制 Bean 是否存在：Provider Bean 必须始终存在，
     * 用 {@code @ConditionalOnProperty} 删 Bean 会让整个上下文启动失败。
     * 关闭端点请覆盖本方法，由 AiServiceFactory 在装配时过滤。</p>
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 是否参与 AUTO 轮转（与健康探测）。
     *
     * <p>置 false 的端点仍可被"指定模型"路由到、也仍出现在模型选择列表里，
     * 只是 AUTO 不会借到它。</p>
     */
    default boolean autoRotate() {
        return enabled();
    }

    /** 优先级，数值越小越优先。 */
    default int priority() {
        return 0;
    }

    /** 连接与「chunk 间空闲」读超时，两者同值（与改造前 connect/read timeout 同源一致）。 */
    default Duration timeout() {
        return Duration.ofSeconds(120);
    }

    /** 同步调用：返回聚合后的全文。 */
    String chat(AiRequest request);

    /** 流式调用：逐块回调，回调在调用线程上按序执行。 */
    void chatStream(AiRequest request, Consumer<String> onChunk);

    /** 健康探测：短请求 + 时限判定，默认实现对绝大多数端点都适用。 */
    default boolean probe() {
        long start = System.nanoTime();
        String response = chat(AiRequest.auto(PROBE_PROMPT, 100));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        return response != null && !response.isBlank() && elapsedMillis <= healthCheckTimeoutMillis();
    }

    /** 健康探测允许的最大响应时间（毫秒）。 */
    default long healthCheckTimeoutMillis() {
        return 10_000L;
    }
}

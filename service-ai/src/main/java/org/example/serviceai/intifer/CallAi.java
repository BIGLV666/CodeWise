package org.example.serviceai.intifer;

import java.util.function.Consumer;

public interface CallAi {
    /**
     * 获取默认AI模型名称
     */
    String getModelName();

    /**
     * 是否参与轮转与健康探测。
     *
     * <p>这里只控制「是否参与」，<b>不控制 Bean 是否存在</b>：Provider Bean 必须始终存在，
     * 因为业务类对它们是硬依赖注入（例如 AiConversationMemaryService 依赖 OllamaService），
     * 用 {@code @ConditionalOnProperty} 直接删 Bean 会导致整个应用上下文启动失败
     * （UnsatisfiedDependencyException）。关闭 Provider 请由实现类读取配置后覆盖本方法，
     * 再由 AIServiceManager 在装配时过滤。</p>
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 调用AI服务
     */
    String callAi(String prompt);
    default String callAi(String prompt, String modelName){return callAi(prompt);}
    default String callAi(String prompt, int maxTokens) {
        return callAi(prompt);
    }
    /**
     * 流式调用ai
     */
    default void streamAi(String prompt, Consumer<String> onChunk) {
        onChunk.accept(callAi(prompt));
    }
    default void streamAi(String prompt, Consumer<String> onChunk, String modelName) {onChunk.accept(callAi(prompt, modelName));}

    /**
     * 检查服务是否可用（健康检查）
     */
    default boolean isAvailable() {
        long start = System.nanoTime();
        String response = callAi("Reply with OK.", 100);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        return response != null
                && !response.isBlank()
                && elapsedMillis <= getHealthCheckTimeout();
    }

    /**
     * 健康探测允许的最大响应时间（毫秒）
     */
    default long getHealthCheckTimeout() {
        return 10000L;
    }

    /**
     * 获取服务优先级（数值越小优先级越高）
     */
    default int getPriority() {
        return 0;
    }

    /**
     * 获取超时时间（毫秒）
     */
    default int getTimeout() {
        return 120000;
    }
}

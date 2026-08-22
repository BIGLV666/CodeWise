package org.example.serviceai.intifer;

import java.util.function.Consumer;

public interface CallAi {
    /**
     * 获取AI模型名称
     */
    String getModelName();

    /**
     * 调用AI服务
     */
    String callAi(String prompt);

    default String callAi(String prompt, int maxTokens) {
        return callAi(prompt);
    }
    /**
     * 流式调用ai
     */
    default void streamAi(String prompt, Consumer<String> onChunk) {
        onChunk.accept(callAi(prompt));
    }

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

package org.example.serviceai.ai.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 一个平台端点的配置侧快照：由 {@link AiProviderCreator} 从 {@code ai.models} 的一条配置翻译而来。
 *
 * <p>单独成对象是为了让创建处<b>按名字</b>传参——十个位置参数的构造器谁都会写错顺序，
 * 而且加一个字段就要改所有调用点。</p>
 *
 * <p>不含 {@link AiKeyPool} 与 {@link AiTransport}：那两个是协作组件，不是端点配置。</p>
 */
public record AiEndpointSpec(
        String identity,
        String endpointUrl,
        String defaultModel,
        Set<String> servedModels,
        AiProtocol protocol,
        AuthScheme authScheme,
        Map<String, String> extraHeaders,
        boolean enabled,
        boolean autoRotate,
        int priority,
        Duration timeout
) {

    public AiEndpointSpec {
        servedModels = servedModels == null ? Set.of() : Set.copyOf(servedModels);
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }
}

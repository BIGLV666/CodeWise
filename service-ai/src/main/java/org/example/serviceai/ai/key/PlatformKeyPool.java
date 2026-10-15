package org.example.serviceai.ai.key;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 平台端点的 key 池（原 {@code GetAPiKey}）：等概率随机取一个，简单负载均衡。
 *
 * <p>改造点只在签名——原来要传整个 {@code Model} 配置对象才能取 key，池和配置被焊死；
 * 现在是装配期 {@link #register(String, List)} 把 key 列表交给池，运行时按端点名取。</p>
 */
@Service
public class PlatformKeyPool implements AiKeyPool {

    private final Map<String, List<String>> keysByEndpoint = new ConcurrentHashMap<>();

    @Override
    public void register(String endpointName, List<String> apiKeys) {
        if (endpointName == null || endpointName.isBlank()) {
            return;
        }
        List<String> usable = apiKeys == null
                ? List.of()
                : apiKeys.stream()
                        .filter(key -> key != null && !key.isBlank())
                        .toList();
        keysByEndpoint.put(endpointName, usable);
    }

    @Override
    public String nextKey(String endpointName) {
        List<String> apiKeys = endpointName == null
                ? List.of()
                : keysByEndpoint.getOrDefault(endpointName, List.of());
        if (apiKeys.isEmpty()) {
            throw new BizException(AiErrorCode.PROVIDER_KEY_INVALID);
        }
        return apiKeys.get(ThreadLocalRandom.current().nextInt(apiKeys.size()));
    }
}

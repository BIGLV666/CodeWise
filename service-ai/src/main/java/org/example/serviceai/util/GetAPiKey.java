package org.example.serviceai.util;

import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 从一个端点的 API Key 池里取一个 key。当前是等概率随机（简单负载均衡），
 * 后续如需按权重或健康度调度可在此扩展。
 */
@Service
public class GetAPiKey {

    public String getApikey(Model model) {
        List<String> apiKeys = model.getApiKeys() == null
                ? List.of()
                : model.getApiKeys().stream()
                        .filter(key -> key != null && !key.isBlank())
                        .toList();
        if (apiKeys.isEmpty()) {
            throw new BizException(AiErrorCode.PROVIDER_KEY_INVALID);
        }
        return apiKeys.get(ThreadLocalRandom.current().nextInt(apiKeys.size()));
    }
}

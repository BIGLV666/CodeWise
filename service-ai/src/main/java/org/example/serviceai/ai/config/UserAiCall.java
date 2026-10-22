package org.example.serviceai.ai.config;

import java.time.Duration;

/**
 * 一次「用户自带配置」调用的完整参数：地址已归一化为 chat/completions 完整地址、
 * 密钥已解密、模型已确认属于该配置。
 *
 * <p>由 {@link UserAiConfigService#requireCallableConfig} 在校验通过后产出，
 * 因此下游（工厂/Provider）拿到它就不需要再做任何数据校验。</p>
 */
public record UserAiCall(
        Long configId,
        String endpointUrl,
        String apiKey,
        String model,
        Duration timeout
) {
}

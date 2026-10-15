package org.example.serviceai.ai.pool;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.AiProviderCreator;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.UserConfigAiProvider;
import org.example.serviceai.ai.transport.AiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 端点的装配工厂：把 {@code ai.models} 的每条配置按 {@link Model#getType()} 交给对应的
 * {@link AiProviderCreator} 变成 Provider。
 *
 * <p>这是「随配置变而变」的唯一入口：普通 OpenAI 兼容端点、走订阅鉴权的端点、
 * 以及将来任何新接入方式，都只是换一个 {@code type} 值，工厂本身不需要改。
 * 未知 type 只跳过该条并记日志，绝不让整个上下文启动失败。</p>
 *
 * <p>用户自带配置（{@code user_ai_config}）不在此按类型装配：它是每请求一变的端点，
 * 由 {@link #createUserConfigProvider} 按需造一次性 Provider，共用同一个
 * {@link AiTransport}（连接池在传输层，两条路径都复用）。</p>
 */
@Service
@Slf4j
public class AiServiceFactory {

    private final AiConfig aiConfig;
    private final AiKeyPool keyPool;
    private final AiTransport transport;
    private final Map<String, AiProviderCreator> creatorsByType;
    private final ObjectProvider<AiProvider> beanProviders;

    public AiServiceFactory(
            AiConfig aiConfig,
            AiKeyPool keyPool,
            AiTransport transport,
            List<AiProviderCreator> creators,
            ObjectProvider<AiProvider> beanProviders
    ) {
        this.aiConfig = aiConfig;
        this.keyPool = keyPool;
        this.transport = transport;
        this.beanProviders = beanProviders;
        this.creatorsByType = new LinkedHashMap<>();
        for (AiProviderCreator creator : creators == null ? List.<AiProviderCreator>of() : creators) {
            AiProviderCreator previous = creatorsByType.put(creator.type(), creator);
            if (previous != null) {
                log.warn("AI 接入类型 {} 有多个 Creator，后注册的生效: {} 覆盖 {}",
                        creator.type(), creator.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        log.info("AI 接入类型注册完成: {}", creatorsByType.keySet());
    }

    /** 按 {@code ai.models} 逐条生成端点；单条配置有问题只跳过它。 */
    public List<AiProvider> createConfiguredProviders() {
        List<Model> models = aiConfig.getModels();
        if (models == null || models.isEmpty()) {
            log.error("未配置 ai.models（或为空），无法装配任何 AI 端点");
            return List.of();
        }

        List<AiProvider> providers = new ArrayList<>(models.size());
        for (Model model : models) {
            try {
                providers.add(create(model));
            } catch (Exception exception) {
                log.error("AI 端点配置无效，已跳过: type={}, name={}",
                        model.getType(), model.getName(), exception);
            }
        }
        warnOnDuplicateIdentities(providers);
        log.info("已按 ai.models 生成 {} 个 AI 端点", providers.size());
        return providers;
    }

    /** 容器内的端点 Bean（如 langchain4j 驱动的 ollama）：按身份可借用，但不进自动轮转。 */
    public List<AiProvider> createBeanProviders() {
        return beanProviders.orderedStream().toList();
    }

    /** 用户自带配置：按需造一次性端点，不参与轮转。 */
    public AiProvider createUserConfigProvider(
            Long configId,
            String endpointUrl,
            String apiKey,
            String model,
            Duration timeout
    ) {
        return new UserConfigAiProvider(configId, endpointUrl, apiKey, model, timeout, transport);
    }

    private AiProvider create(Model model) {
        String type = model.getType() == null || model.getType().isBlank()
                ? OpenAiProviderCreator.TYPE
                : model.getType().trim();
        AiProviderCreator creator = creatorsByType.get(type);
        if (creator == null) {
            throw new BizException(AiErrorCode.PROVIDER_TYPE_UNSUPPORTED);
        }

        AiProvider provider = creator.create(model, keyPool, transport);
        // 端点身份要等 Provider 造出来才知道，所以 key 池注册放在创建之后——
        // Provider 只在调用期取 key，装配期注册的顺序不影响它。
        keyPool.register(provider.name(), model.getApiKeys());
        warnOnModelDeclaration(model, provider.name());
        return provider;
    }

    /**
     * 模型声明与默认模型不一致时告警。
     *
     * <p>不硬失败：线上 Nacos 的存量配置可能就是这样，启动不了比行为不理想严重得多。
     * 「默认模型不在声明列表里」的处理是把它并入路由索引（见
     * {@code AiProviderCreators.servedModelsOf}），保证 AUTO 正在用的模型在模型选择
     * 列表里也一定选得到。</p>
     */
    private void warnOnModelDeclaration(Model model, String identity) {
        String defaultModel = model.getDefaultModelName();
        if (defaultModel == null || defaultModel.isBlank()) {
            log.warn("AI 端点未配置 default-model-name，AUTO 借到它时没有模型可用: identity={}", identity);
            return;
        }
        List<String> declared = model.getModelName();
        if (declared != null && !declared.isEmpty() && !declared.contains(defaultModel)) {
            log.warn("AI 端点的 default-model-name 不在 model-name 列表里，已并入模型路由索引: "
                            + "identity={}, defaultModel={}, declared={}",
                    identity, defaultModel, declared);
        }
    }

    /**
     * 端点身份重复时告警。
     *
     * <p>身份同时是健康状态与 key 池的键：重名会让两个端点共享失败计数，
     * 并且后注册的 key 列表覆盖前者的（把 A 端点的地址配上 B 端点的密钥）。</p>
     */
    private void warnOnDuplicateIdentities(List<AiProvider> providers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            counts.merge(String.valueOf(provider.name()), 1, Integer::sum);
        }
        counts.forEach((identity, count) -> {
            if (count > 1) {
                log.warn("AI 端点身份重复: identity={} 被 {} 个端点共用，"
                        + "它们会共享健康状态与 key 池，请在 ai.models[].name 上区分", identity, count);
            }
        });
    }
}

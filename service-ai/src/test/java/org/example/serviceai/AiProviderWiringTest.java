package org.example.serviceai;

import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.pool.AiPool;
import org.example.serviceai.ai.pool.AiServiceFactory;
import org.example.serviceai.ai.pool.DefaultAiPool;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.OpenCodeGoProviderCreator;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配冒烟（不依赖 MySQL/Redis/RabbitMQ/Nacos）。
 *
 * <p>核心回归点：配置驱动的 Provider <b>不是容器 Bean</b>——它们由 AiServiceFactory 按
 * {@code ai.models} 逐条创建。改造前这条差点被破坏（{@code AiAggregateService} 曾是
 * {@code @Service} 且依赖不是 Bean 的 Model，导致整个上下文启动失败）。</p>
 */
class AiProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void contextStartsWithNoModelsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // 关键回归：配置驱动的 Provider 不在容器里
            assertThat(context.getBeansOfType(AiProvider.class)).isEmpty();

            AiPool pool = context.getBean(AiPool.class);
            assertThat(pool.status()).isEmpty();
            assertThatThrownBy(pool::borrowAuto)
                    .isInstanceOf(BizException.class)
                    .extracting(exception -> ((BizException) exception).getCode())
                    .isEqualTo(AiErrorCode.AI_SERVICE_NOT_READY.getCode());
        });
    }

    @Test
    void contextStartsAndServesConfiguredProvider() {
        runner.withPropertyValues(
                "ai.health-check.enabled=false",
                "ai.models[0].name=GLM",
                "ai.models[0].url=https://example.com/v1/chat/completions",
                "ai.models[0].default-model-name=glm-4-flash",
                "ai.models[0].api-keys[0]=sk-a",
                "ai.models[0].priority=1"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            AiPool pool = context.getBean(AiPool.class);
            // 端点身份是 ai.models[].name（GLM），不是模型名——这是本次拆分的核心区分
            assertThat(pool.borrowAuto().name()).isEqualTo("GLM");
            assertThat(pool.status()).containsKey("GLM");
            // 配置里没写 type 也必须能用（线上 Nacos 配置就缺这个字段）
            assertThat(pool.borrowByIdentity("GLM")).isNotNull();

            // key 已按端点身份注册进池，Provider 运行时不持有 key 列表
            AiKeyPool keyPool = context.getBean(AiKeyPool.class);
            assertThat(keyPool.nextKey("GLM")).isEqualTo("sk-a");

            // 端点被创建成对象，但仍然不是容器 Bean
            assertThat(context.getBeansOfType(AiProvider.class)).isEmpty();
        });
    }

    @Test
    void openCodeGoTypeIsAssembledFromConfiguration() {
        runner.withPropertyValues(
                "ai.health-check.enabled=false",
                "ai.models[0].name=opencode",
                "ai.models[0].type=opencode-go",
                "ai.models[0].url=https://example.com/v1/chat/completions",
                "ai.models[0].default-model-name=opencode-model",
                "ai.models[0].api-keys[0]=session-token"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiPool.class).borrowAuto().name()).isEqualTo("opencode");
        });
    }

    @Test
    void unknownTypeIsSkippedWithoutBreakingTheContext() {
        runner.withPropertyValues(
                "ai.health-check.enabled=false",
                "ai.models[0].name=GLM",
                "ai.models[0].url=https://example.com/v1/chat/completions",
                "ai.models[0].default-model-name=glm-4-flash",
                "ai.models[0].api-keys[0]=sk-a",
                "ai.models[1].name=typo",
                "ai.models[1].type=no-such-protocol",
                "ai.models[1].default-model-name=broken",
                "ai.models[1].api-keys[0]=sk-b"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            // 坏的那条只跳过自己，好的照常可用
            assertThat(context.getBean(AiPool.class).status()).containsOnlyKeys("GLM");
        });
    }

    @EnableConfigurationProperties(AiConfig.class)
    @Import({
            PlatformKeyPool.class,
            HttpAiTransport.class,
            OpenAiProviderCreator.class,
            OpenCodeGoProviderCreator.class,
            AiServiceFactory.class,
            DefaultAiPool.class
    })
    static class TestConfig {
    }
}

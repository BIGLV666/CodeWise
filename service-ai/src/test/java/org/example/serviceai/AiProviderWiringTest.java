package org.example.serviceai;

import org.example.serviceai.AIServiceManager.AIServiceManager;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.util.GetAPiKey;
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
 * <p>这是本次重构最核心的回归点：改造前 {@code AiAggregateService} 是 {@code @Service}
 * 且 {@code @Autowired Model}，Model 不是 Bean → 整个上下文启动失败。这里断言
 * 「上下文能起来」且 AiAggregateService 不再是容器里的 Bean（它由 AIServiceManager 按配置生成）。</p>
 */
class AiProviderWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void contextStartsWithNoModelsConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // 关键回归：AiAggregateService 不再是注册进容器的 CallAi Bean
            assertThat(context.getBeansOfType(CallAi.class)).isEmpty();

            AIServiceManager manager = context.getBean(AIServiceManager.class);
            assertThatThrownBy(manager::getAvailableService)
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

            AIServiceManager manager = context.getBean(AIServiceManager.class);
            assertThat(manager.getAvailableService().getModelName()).isEqualTo("glm-4-flash");
            assertThat(manager.getAllServiceStatus()).containsKey("glm-4-flash");
        });
    }

    @EnableConfigurationProperties(AiConfig.class)
    @Import({GetAPiKey.class, AIServiceManager.class})
    static class TestConfig {
    }
}

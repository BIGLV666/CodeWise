package org.example.serviceai.config;

import org.example.serviceai.entry.Model;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ai.*} 绑定：{@code ai.models[]} 各字段、{@code ai.health-check.enabled}，
 * 以及缺省值（enabled=true、timeout=120s）——缺省值错会让线上行为整体反转，必须锁住。
 */
class AiConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsWhenNothingConfigured() {
        runner.run(context -> {
            AiConfig config = context.getBean(AiConfig.class);
            assertThat(config.isHealthCheckEnabled()).isTrue();
            assertThat(config.getModels()).isNullOrEmpty();
        });
    }

    @Test
    void bindsEveryModelFieldAndHealthCheckFlag() {
        runner.withPropertyValues(
                "ai.health-check.enabled=false",
                "ai.models[0].name=GLM",
                "ai.models[0].url=https://example.com/v1/chat/completions",
                "ai.models[0].model-name[0]=glm-4-flash",
                "ai.models[0].model-name[1]=glm-4-air",
                "ai.models[0].default-model-name=glm-4-flash",
                "ai.models[0].api-keys[0]=sk-a",
                "ai.models[0].api-keys[1]=sk-b",
                "ai.models[0].enabled=false",
                "ai.models[0].priority=3",
                "ai.models[0].timeout=1234"
        ).run(context -> {
            AiConfig config = context.getBean(AiConfig.class);
            assertThat(config.isHealthCheckEnabled()).isFalse();
            assertThat(config.getModels()).hasSize(1);

            Model model = config.getModels().get(0);
            assertThat(model.getName()).isEqualTo("GLM");
            assertThat(model.getUrl()).isEqualTo("https://example.com/v1/chat/completions");
            assertThat(model.getModelName()).containsExactly("glm-4-flash", "glm-4-air");
            assertThat(model.getDefaultModelName()).isEqualTo("glm-4-flash");
            assertThat(model.getApiKeys()).containsExactly("sk-a", "sk-b");
            assertThat(model.isEnabled()).isFalse();
            assertThat(model.getPriority()).isEqualTo(3);
            assertThat(model.getTimeout()).isEqualTo(1234);
        });
    }

    @Test
    void perModelDefaultsAreEnabledAnd120sTimeout() {
        runner.withPropertyValues(
                "ai.models[0].name=X",
                "ai.models[0].url=https://example.com/v1/chat/completions"
        ).run(context -> {
            Model model = context.getBean(AiConfig.class).getModels().get(0);
            assertThat(model.isEnabled()).isTrue();
            assertThat(model.getTimeout()).isEqualTo(120000);
        });
    }

    @EnableConfigurationProperties(AiConfig.class)
    static class TestConfig {
    }
}

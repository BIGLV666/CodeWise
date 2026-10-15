package org.example.serviceai.ai.pool;

import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.OpenCodeGoProviderCreator;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「模型 → 端点」索引：用户选了平台模型之后，靠它找到该打哪个端点。
 *
 * <p>索引与端点身份是<b>两套键空间</b>：身份（{@code ai.models[].name}）回答"这个端点是谁"，
 * 模型回答"这个能力谁能提供"。改造前只有一套、且拿模型名当身份用，所以模型选择根本无法表达。</p>
 */
class DefaultAiPoolModelIndexTest {

    private static final String DEAD_URL = "http://127.0.0.1:1/v1/chat/completions";

    @Test
    void platformModelsAreDeduplicatedAndSorted() {
        DefaultAiPool pool = poolOf(configOf(
                model("s-a", "m-1", 1, true, "m-1"),
                model("s-b", "m-2", 2, true, "m-2", "m-1")   // 与 s-a 重复声明 m-1
        ));

        assertThat(pool.platformModels()).containsExactly("m-1", "m-2");
    }

    @Test
    void everyListedModelIsActuallyRoutable() {
        DefaultAiPool pool = poolOf(configOf(
                model("s-a", "m-1", 1, true, "m-1", "m-2"),
                model("s-b", "m-3", 2, true, "m-3")
        ));

        // 目录列出来的每个模型都必须真的路由得到——否则前端会给出一个必然失败的选项
        for (String model : pool.platformModels()) {
            assertThat(pool.borrowForModel(model))
                    .as("模型 %s 出现在目录里却借不到端点", model)
                    .isNotNull();
        }
    }

    @Test
    void unknownModelIsNotRoutable() {
        DefaultAiPool pool = poolOf(configOf(model("s-a", "m-1", 1, true, "m-1")));

        assertThat(pool.borrowForModel("never-declared")).isNull();
        assertThat(pool.borrowForModel(null)).isNull();
    }

    @Test
    void disabledEndpointDropsOutOfTheModelIndex() {
        DefaultAiPool pool = poolOf(configOf(
                model("s-a", "m-1", 1, true, "m-1"),
                model("s-b", "m-2", 2, false, "m-2", "m-only-on-disabled")
        ));

        // enabled=false 是运维在说"别用这个端点"，模型选择列表与路由都不该再出现它
        assertThat(pool.platformModels()).containsExactly("m-1");
        assertThat(pool.borrowForModel("m-2")).isNull();
        assertThat(pool.borrowForModel("m-only-on-disabled")).isNull();
    }

    @Test
    void defaultModelIsMergedInEvenWhenNotDeclared() {
        // defaultModelName 不在 modelName 列表里：AUTO 实际在用它，必须也能被显式选中
        DefaultAiPool pool = poolOf(configOf(model("s-a", "not-declared", 1, true, "m-1")));

        assertThat(pool.platformModels()).containsExactly("m-1", "not-declared");
        assertThat(pool.borrowForModel("not-declared")).isNotNull();
    }

    @Test
    void endpointBeanProviderIsNotRoutableByTheModelsItClaims() {
        // 容器内的端点 Bean（如 ollama）不进轮转集合，因此也不进模型索引——
        // 即使用户的模型名恰好撞上它自报的模型，也不该被路由到它
        AiProvider beanEndpoint = new StubProvider("ollama", Set.of("ollama-model"));
        DefaultAiPool pool = poolOf(configOf(model("s-a", "m-1", 1, true, "m-1")), beanEndpoint);

        assertThat(pool.platformModels()).containsExactly("m-1");
        assertThat(pool.borrowForModel("ollama-model")).isNull();
        // 但它仍然按身份借得到（摘要路径依赖这一点）
        assertThat(pool.borrowByIdentity("ollama")).isSameAs(beanEndpoint);
    }

    @Test
    void sameModelDeclaredBySeveralEndpointsIsSpreadAcrossThem() {
        DefaultAiPool pool = poolOf(configOf(
                model("s-a", "m-1", 1, true, "shared"),
                model("s-b", "m-2", 2, true, "shared"),
                model("s-c", "m-3", 3, true, "shared")
        ));

        Set<String> served = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            AiProvider provider = pool.borrowForModel("shared");
            assertThat(provider).isNotNull();
            served.add(provider.name());
        }

        // 同一模型被多个端点声明时，指定模型这条路要能把流量散到全部候选上
        assertThat(served).containsExactlyInAnyOrder("s-a", "s-b", "s-c");
    }

    @Test
    void emptyConfigYieldsNoModels() {
        DefaultAiPool pool = poolOf(configOf());

        assertThat(pool.platformModels()).isEmpty();
        assertThat(pool.borrowForModel("m-1")).isNull();
    }

    private static DefaultAiPool poolOf(AiConfig config, AiProvider... beanProviders) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < beanProviders.length; i++) {
            beanFactory.registerSingleton("bean-" + i, beanProviders[i]);
        }
        ObjectProvider<AiProvider> beans = beanFactory.getBeanProvider(AiProvider.class);

        AiServiceFactory factory = new AiServiceFactory(
                config,
                new PlatformKeyPool(),
                new HttpAiTransport(),
                List.of(new OpenAiProviderCreator(), new OpenCodeGoProviderCreator()),
                beans
        );
        DefaultAiPool pool = new DefaultAiPool(factory, config);
        pool.init();
        return pool;
    }

    private static AiConfig configOf(Model... models) {
        AiConfig config = new AiConfig();
        config.setHealthCheckEnabled(false);
        config.setModels(models.length == 0 ? List.of() : List.of(models));
        return config;
    }

    private static Model model(
            String identity,
            String defaultModel,
            int priority,
            boolean enabled,
            String... declaredModels
    ) {
        Model model = new Model();
        model.setName(identity);
        model.setUrl(DEAD_URL);
        model.setModelName(List.of(declaredModels));
        model.setDefaultModelName(defaultModel);
        model.setApiKeys(List.of("sk-" + identity));
        model.setPriority(priority);
        model.setEnabled(enabled);
        model.setTimeout(2000);
        return model;
    }

    private static final class StubProvider implements AiProvider {

        private final String identity;
        private final Set<String> served;

        private StubProvider(String identity, Set<String> served) {
            this.identity = identity;
            this.served = served;
        }

        @Override
        public String name() {
            return identity;
        }

        @Override
        public Set<String> servedModels() {
            return served;
        }

        @Override
        public String chat(AiRequest request) {
            throw new UnsupportedOperationException("stub 不应被调用");
        }

        @Override
        public void chatStream(AiRequest request, Consumer<String> onChunk) {
            throw new UnsupportedOperationException("stub 不应被调用");
        }
    }
}

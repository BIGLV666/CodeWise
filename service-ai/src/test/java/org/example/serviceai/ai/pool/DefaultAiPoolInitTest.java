package org.example.serviceai.ai.pool;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.OpenCodeGoProviderCreator;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端点池装配：按 {@code ai.models} 生成端点、按 priority 排序、过滤 enabled=false、
 * 健康探测开关的两条路径、失败计数切换，以及未配置/全关闭的降级。
 *
 * <p>断言与改造前的 {@code AIServiceManagerInitTest} 逐条对应——状态机行为不允许漂移。</p>
 */
class DefaultAiPoolInitTest {

    private static final String DEAD_URL = "http://127.0.0.1:1/v1/chat/completions";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buildsSortedProvidersAndFiltersDisabled() {
        DefaultAiPool pool = poolOf(configOf(false,
                model("s-a", "m-a", 5, true),
                model("s-b", "m-b", 1, true),
                model("s-c", "m-c", 2, false)));

        assertThat(new ArrayList<>(pool.status().keySet())).containsExactly("s-b", "s-a");
        assertThat(pool.borrowAuto().name()).isEqualTo("s-b");
        assertThat(pool.switchTo("s-a")).isTrue();
        assertThat(pool.borrowAuto().name()).isEqualTo("s-a");
        assertThat(pool.switchTo("s-c")).isFalse();
    }

    @Test
    void healthCheckDisabledMarksEveryProviderAvailable() {
        DefaultAiPool pool = poolOf(configOf(false,
                model("s-a", "m-a", 5, true),
                model("s-b", "m-b", 1, true)));

        assertThat(pool.status())
                .containsOnlyKeys("s-a", "s-b")
                .allSatisfy((name, healthy) -> assertThat(healthy).isTrue());
    }

    @Test
    void noModelsMeansNothingUsable() {
        DefaultAiPool pool = poolOf(configOf(false));

        assertThat(pool.status()).isEmpty();
        assertThatThrownBy(pool::borrowAuto)
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.AI_SERVICE_NOT_READY.getCode());
    }

    @Test
    void allDisabledMeansNothingUsable() {
        DefaultAiPool pool = poolOf(configOf(false,
                model("s-a", "m-a", 1, false),
                model("s-b", "m-b", 2, false)));

        assertThat(pool.status()).isEmpty();
        assertThatThrownBy(pool::borrowAuto).isInstanceOf(BizException.class);
    }

    @Test
    void repeatedFailuresMarkProviderUnhealthyAndSwitchAway() {
        DefaultAiPool pool = poolOf(configOf(false,
                model("s-a", "m-a", 1, true),
                model("s-b", "m-b", 2, true)));

        AiProvider first = pool.borrowAuto();
        assertThat(first.name()).isEqualTo("s-a");

        for (int i = 0; i < 3; i++) {
            pool.reportFailure(first);
        }

        assertThat(pool.status().get("s-a")).isFalse();
        assertThat(pool.borrowAuto().name()).isEqualTo("s-b");

        // 一次成功即清零失败计数并恢复可用
        pool.reportSuccess(first);
        assertThat(pool.status().get("s-a")).isTrue();
    }

    @Test
    void healthCheckProbesRealEndpointAndMarksItHealthy() throws Exception {
        String endpoint = startSseServer();
        DefaultAiPool pool = poolOf(configOf(true, modelWithUrl("s-a", "m-a", 1, true, endpoint)));

        try {
            await(() -> Boolean.TRUE.equals(pool.status().get("s-a")), 5000);
            assertThat(pool.borrowAuto()).isNotNull();
        } finally {
            pool.stopHealthCheck();
        }
    }

    @Test
    void unreachableEndpointStaysUnhealthyAndPoolDegrades() throws Exception {
        int closedPort = closedPort();
        DefaultAiPool pool = poolOf(configOf(true,
                modelWithUrl("s-a", "m-a", 1, true,
                        "http://127.0.0.1:" + closedPort + "/v1/chat/completions")));

        try {
            await(() -> Boolean.FALSE.equals(pool.status().get("s-a")), 5000);
            // 降级：没有健康端点时返回 null 而不是抛异常，由 Facade 决定怎么处理
            assertThat(pool.borrowAuto()).isNull();
        } finally {
            pool.stopHealthCheck();
        }
    }

    /**
     * 容器内的端点 Bean（如 ollama）按名可借用，但不进自动轮转、不参与健康记账。
     *
     * <p>这是改造前「摘要路径直连 OllamaService、绕过管理器」行为的等价表达。</p>
     */
    @Test
    void beanProviderIsBorrowableByNameWithoutJoiningRotation() {
        AiProvider beanEndpoint = new StubProvider("ollama");
        DefaultAiPool pool = poolOf(configOf(false, model("s-a", "m-a", 1, true)), beanEndpoint);

        assertThat(pool.status()).containsOnlyKeys("s-a");
        assertThat(pool.borrowByIdentity("ollama")).isSameAs(beanEndpoint);
        assertThat(pool.borrowByIdentity("absent")).isNull();

        // 记账对不在轮转集合里的端点是无操作：不会把它标成不可用、也不会切换
        pool.reportFailure(beanEndpoint);
        pool.reportFailure(beanEndpoint);
        pool.reportFailure(beanEndpoint);
        assertThat(pool.borrowAuto().name()).isEqualTo("s-a");
    }

    /** 配置一条都没有时，端点 Bean 仍然要能被按名借到（否则摘要路径会跟着挂）。 */
    @Test
    void beanProviderSurvivesEmptyModelConfig() {
        AiProvider beanEndpoint = new StubProvider("ollama");
        DefaultAiPool pool = poolOf(configOf(false), beanEndpoint);

        assertThat(pool.status()).isEmpty();
        assertThat(pool.borrowByIdentity("ollama")).isSameAs(beanEndpoint);
    }

    private String startSseServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] payload = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("条件在 " + timeoutMillis + "ms 内未满足");
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

    private static AiConfig configOf(boolean healthCheckEnabled, Model... models) {
        AiConfig config = new AiConfig();
        config.setHealthCheckEnabled(healthCheckEnabled);
        config.setModels(models.length == 0 ? List.of() : List.of(models));
        return config;
    }

    private static Model model(String name, String defaultModel, int priority, boolean enabled) {
        return modelWithUrl(name, defaultModel, priority, enabled, DEAD_URL);
    }

    private static Model modelWithUrl(
            String name,
            String defaultModel,
            int priority,
            boolean enabled,
            String url
    ) {
        Model model = new Model();
        model.setName(name);
        model.setUrl(url);
        model.setModelName(List.of(defaultModel, defaultModel + "-alt"));
        model.setDefaultModelName(defaultModel);
        model.setApiKeys(List.of("sk-" + name.toLowerCase()));
        model.setPriority(priority);
        model.setEnabled(enabled);
        model.setTimeout(2000);
        return model;
    }

    /** 只用于「按名可借用」的断言，不做任何真实调用。 */
    private static final class StubProvider implements AiProvider {

        private final String name;

        private StubProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
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

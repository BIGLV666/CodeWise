package org.example.serviceai.AIServiceManager;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.util.GetAPiKey;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link AIServiceManager} 装配：按 {@code ai.models} 生成端点、按 priority 排序、
 * 过滤 enabled=false、健康探测开关的两条路径、失败计数切换，以及未配置/全关闭的降级。
 */
class AIServiceManagerInitTest {

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
        AiConfig config = configOf(false,
                model("A", "m-a", 5, true),
                model("B", "m-b", 1, true),
                model("C", "m-c", 2, false));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();

        assertThat(new ArrayList<>(manager.getAllServiceStatus().keySet()))
                .containsExactly("m-b", "m-a");
        assertThat(manager.getAvailableService().getModelName()).isEqualTo("m-b");
        assertThat(manager.switchToService("m-a")).isTrue();
        assertThat(manager.getAvailableService().getModelName()).isEqualTo("m-a");
        assertThat(manager.switchToService("m-c")).isFalse();
    }

    @Test
    void healthCheckDisabledMarksEveryProviderAvailable() {
        AiConfig config = configOf(false,
                model("A", "m-a", 5, true),
                model("B", "m-b", 1, true));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();

        assertThat(manager.getAllServiceStatus())
                .containsOnlyKeys("m-a", "m-b")
                .allSatisfy((name, healthy) -> assertThat(healthy).isTrue());
    }

    @Test
    void noModelsMeansNothingUsable() {
        AiConfig config = configOf(false);

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();

        assertThat(manager.getAllServiceStatus()).isEmpty();
        assertThatThrownBy(manager::getAvailableService)
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.AI_SERVICE_NOT_READY.getCode());
    }

    @Test
    void allDisabledMeansNothingUsable() {
        AiConfig config = configOf(false,
                model("A", "m-a", 1, false),
                model("B", "m-b", 2, false));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();

        assertThat(manager.getAllServiceStatus()).isEmpty();
        assertThatThrownBy(manager::getAvailableService).isInstanceOf(BizException.class);
    }

    @Test
    void repeatedFailuresMarkProviderUnhealthyAndSwitchAway() {
        AiConfig config = configOf(false,
                model("A", "m-a", 1, true),
                model("B", "m-b", 2, true));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();

        CallAi first = manager.getAvailableService();
        assertThat(first.getModelName()).isEqualTo("m-a");

        for (int i = 0; i < 3; i++) {
            manager.recordFailure(first);
        }

        assertThat(manager.getAllServiceStatus().get("m-a")).isFalse();
        assertThat(manager.getAvailableService().getModelName()).isEqualTo("m-b");

        // 一次成功即清零失败计数并恢复可用
        manager.recordSuccess(first);
        assertThat(manager.getAllServiceStatus().get("m-a")).isTrue();
    }

    @Test
    void healthCheckProbesRealEndpointAndMarksItHealthy() throws Exception {
        String endpoint = startSseServer();
        AiConfig config = configOf(true, modelWithUrl("A", "m-a", 1, true, endpoint));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        try {
            manager.init();
            await(() -> Boolean.TRUE.equals(manager.getAllServiceStatus().get("m-a")), 5000);
            assertThat(manager.getAvailableService()).isNotNull();
        } finally {
            manager.stopHealthCheck();
        }
    }

    @Test
    void unreachableEndpointStaysUnhealthyAndManagerDegrades() throws Exception {
        int closedPort = closedPort();
        AiConfig config = configOf(true,
                modelWithUrl("A", "m-a", 1, true, "http://127.0.0.1:" + closedPort + "/v1/chat/completions"));

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        try {
            manager.init();
            await(() -> Boolean.FALSE.equals(manager.getAllServiceStatus().get("m-a")), 5000);
            assertThat(manager.getAvailableService()).isNull();
        } finally {
            manager.stopHealthCheck();
        }
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
}

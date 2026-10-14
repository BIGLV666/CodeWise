package org.example.serviceai.service;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.util.GetAPiKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiAggregateService}：用本地 SSE mock server 验证 URL / 模型参数 / apiKey 轮换 /
 * 分块拼接 / HTTP 错误分类，以及 Model 缺省值（priority、timeout、enabled）。
 */
class AiAggregateServiceTest {

    private static final String SSE_TWO_CHUNKS =
            "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
                    + "data: [DONE]\n\n";

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = SSE_TWO_CHUNKS;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth == null ? "" : auth);
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            if (status >= 300) {
                exchange.getResponseHeaders().add("Retry-After", "7");
            } else {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void modelDefaultsApplyWhenUnset() {
        Model model = new Model();
        model.setUrl(endpoint);
        model.setDefaultModelName("glm-4-flash");
        model.setApiKeys(List.of("sk-a"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        assertEquals("glm-4-flash", service.getModelName());
        assertTrue(service.isEnabled(), "enabled 缺省应为 true，否则端点会被全部过滤");
        assertEquals(0, service.getPriority(), "priority 为 null 时应兜底 0，不能 NPE");
        assertEquals(120000, service.getTimeout(), "timeout 缺省应为 120s，0 会变成无限等待");
    }

    @Test
    void disabledModelIsReportedAsDisabled() {
        Model model = baseModel(List.of("sk-a"));
        model.setEnabled(false);

        assertFalse(new AiAggregateService(model, new GetAPiKey()).isEnabled());
    }

    @Test
    void timeoutAndPriorityComeFromModel() {
        Model model = baseModel(List.of("sk-a"));
        model.setTimeout(4321);
        model.setPriority(7);
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        assertEquals(4321, service.getTimeout());
        assertEquals(7, service.getPriority());
    }

    @Test
    void callAiUsesDefaultModelAndJoinsChunks() {
        Model model = baseModel(List.of("sk-a"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        assertEquals("hello", service.callAi("hi"));
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-flash\""), lastBody.get());
        assertEquals("Bearer sk-a", authHeaders.get(0));
    }

    @Test
    void explicitModelOverridesDefaultInRequestBody() {
        Model model = baseModel(List.of("sk-a"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        assertEquals("hello", service.callAi("hi", "glm-4-air"));
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-air\""), lastBody.get());
    }

    @Test
    void explicitModelStreamsIncrementallyInsteadOfBuffering() {
        Model model = baseModel(List.of("sk-a"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        List<String> chunks = new ArrayList<>();
        service.streamAi("hi", chunks::add, "glm-4-air");

        assertEquals(List.of("he", "llo"), chunks, "应逐块回调，而不是缓冲后一次性吐出");
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-air\""), lastBody.get());
    }

    @Test
    void apiKeysAreRotatedAcrossCalls() {
        Model model = baseModel(List.of("sk-a", "sk-b", "sk-c"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        for (int i = 0; i < 200; i++) {
            assertEquals("hello", service.callAi("hi"));
        }

        Set<String> used = Set.copyOf(authHeaders);
        assertEquals(Set.of("Bearer sk-a", "Bearer sk-b", "Bearer sk-c"), used,
                "200 次调用应覆盖全部 key，且不会用到池外的 key");
    }

    @Test
    void httpErrorIsClassifiedByStatus() {
        Model model = baseModel(List.of("sk-a"));
        AiAggregateService service = new AiAggregateService(model, new GetAPiKey());

        status = 500;
        responseBody = "boom";
        AiProviderHttpException serverError =
                assertThrows(AiProviderHttpException.class, () -> service.callAi("hi"));
        assertEquals(500, serverError.getStatusCode());
        assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getCode(), serverError.getCode());

        status = 401;
        AiProviderHttpException authError =
                assertThrows(AiProviderHttpException.class, () -> service.callAi("hi"));
        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getCode(), authError.getCode());
        // 上游状态码/响应体只进诊断信息，不进面向用户的消息
        assertTrue(authError.getDiagnostic().contains("HTTP 401"), authError.getDiagnostic());
        assertFalse(authError.getMessage().contains("401"), authError.getMessage());
    }

    private Model baseModel(List<String> apiKeys) {
        Model model = new Model();
        model.setName("GLM");
        model.setUrl(endpoint);
        model.setModelName(List.of("glm-4-flash", "glm-4-air"));
        model.setDefaultModelName("glm-4-flash");
        model.setApiKeys(apiKeys);
        return model;
    }
}

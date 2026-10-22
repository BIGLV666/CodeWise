package org.example.serviceai.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.AiTarget;
import org.example.serviceai.ai.transport.AiProviderHttpException;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台端点的通用实现：URL / 模型参数 / apiKey 轮换 / 分块拼接 / HTTP 错误分类 /
 * 重定向拒绝 / 附加头透传，以及 Model 缺省值（priority、timeout、enabled）。
 */
class OpenAiCompatibleProviderTest {

    private static final String SSE_TWO_CHUNKS =
            "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
                    + "data: [DONE]\n\n";
    /** SSE 规范允许 data: 后不带空格，部分网关就是这么发的——只认 "data: " 会静默丢块。 */
    private static final String SSE_NO_SPACE_AFTER_DATA =
            "data:{\"choices\":[{\"delta\":{\"content\":\"tight\"}}]}\n\n"
                    + "data:[DONE]\n\n";

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sessionHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sessionIdHeaders = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = SSE_TWO_CHUNKS;
    private volatile String location;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("x-opencode-session"));
            sessionIdHeaders.add(exchange.getRequestHeaders().getFirst("x-opencode-session-id"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            if (status >= 300) {
                exchange.getResponseHeaders().add("Retry-After", "7");
            } else {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
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
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        assertEquals("GLM", provider.name(), "端点身份来自 ai.models[].name，不是模型名");
        assertTrue(provider.enabled(), "enabled 缺省应为 true，否则端点会被全部过滤");
        assertEquals(0, provider.priority(), "priority 为 null 时应兜底 0，不能 NPE");
        assertEquals(Duration.ofSeconds(120), provider.timeout(), "timeout 缺省应为 120s");
    }

    @Test
    void disabledModelIsReportedAsDisabled() {
        Model model = baseModel(List.of("sk-a"));
        model.setEnabled(false);

        assertFalse(providerOf(model).enabled());
    }

    @Test
    void timeoutAndPriorityComeFromModel() {
        Model model = baseModel(List.of("sk-a"));
        model.setTimeout(4321);
        model.setPriority(7);
        OpenAiCompatibleProvider provider = providerOf(model);

        assertEquals(Duration.ofMillis(4321), provider.timeout());
        assertEquals(7, provider.priority());
    }

    @Test
    void chatUsesDefaultModelAndJoinsChunks() {
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        assertEquals("hello", provider.chat(request("hi", null, null)));
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-flash\""), lastBody.get());
        assertEquals("Bearer sk-a", authHeaders.get(0));
    }

    @Test
    void explicitModelOverridesDefaultInRequestBody() {
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        assertEquals("hello", provider.chat(request("hi", "glm-4-air", null)));
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-air\""), lastBody.get());
    }

    @Test
    void explicitModelStreamsIncrementallyInsteadOfBuffering() {
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        List<String> chunks = new ArrayList<>();
        provider.chatStream(request("hi", "glm-4-air", null), chunks::add);

        assertEquals(List.of("he", "llo"), chunks, "应逐块回调，而不是缓冲后一次性吐出");
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-air\""), lastBody.get());
    }

    @Test
    void apiKeysAreRotatedAcrossCalls() {
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a", "sk-b", "sk-c")));

        for (int i = 0; i < 200; i++) {
            assertEquals("hello", provider.chat(request("hi", null, null)));
        }

        Set<String> used = Set.copyOf(authHeaders);
        assertEquals(Set.of("Bearer sk-a", "Bearer sk-b", "Bearer sk-c"), used,
                "200 次调用应覆盖全部 key，且不会用到池外的 key");
    }

    @Test
    void httpErrorIsClassifiedByStatus() {
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        status = 500;
        responseBody = "boom";
        AiProviderHttpException serverError =
                assertThrows(AiProviderHttpException.class, () -> provider.chat(request("hi", null, null)));
        assertEquals(500, serverError.getStatusCode());
        assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getCode(), serverError.getCode());

        status = 401;
        AiProviderHttpException authError =
                assertThrows(AiProviderHttpException.class, () -> provider.chat(request("hi", null, null)));
        assertEquals(AiErrorCode.PROVIDER_KEY_INVALID.getCode(), authError.getCode());
        // 上游状态码/响应体只进诊断信息，不进面向用户的消息
        assertTrue(authError.getDiagnostic().contains("HTTP 401"), authError.getDiagnostic());
        assertFalse(authError.getMessage().contains("401"), authError.getMessage());

        status = 429;
        AiProviderHttpException quotaError =
                assertThrows(AiProviderHttpException.class, () -> provider.chat(request("hi", null, null)));
        assertEquals(AiErrorCode.PROVIDER_QUOTA_EXCEEDED.getCode(), quotaError.getCode());
        assertEquals("7", quotaError.getRetryAfter());
    }

    @Test
    void trailingUsageChunkWithEmptyChoicesIsNotTreatedAsContent() {
        // 开了 stream_options.include_usage 之后，上游会在流末尾补一个 choices 为空数组的
        // usage 分片；它不能被当成正文，也不能让解析器取 choices[0] 越界
        responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"total_tokens\":7}}\n\n"
                + "data: [DONE]\n\n";
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        assertEquals("hi", provider.chat(request("hi", null, null)));
    }

    @Test
    void redirectedEndpointIsRejectedInsteadOfFollowed() {
        status = 307;
        responseBody = "";
        location = "http://127.0.0.1:1/internal";
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        BizException rejected = assertThrows(BizException.class, () -> provider.chat(request("hi", null, null)));
        assertEquals(AiErrorCode.AI_URL_INVALID.getCode(), rejected.getCode());
    }

    @Test
    void sseChunksWithoutSpaceAfterDataPrefixAreNotDropped() {
        responseBody = SSE_NO_SPACE_AFTER_DATA;
        OpenAiCompatibleProvider provider = providerOf(baseModel(List.of("sk-a")));

        assertEquals("tight", provider.chat(request("hi", null, null)));
    }

    @Test
    void openCodeGoSendsBearerAuthPlusSessionHeadersAndRicherBody() {
        Model model = baseModel(List.of("oc_sk_demo"));
        model.setType(OpenCodeGoProviderCreator.TYPE);
        OpenAiCompatibleProvider provider = (OpenAiCompatibleProvider) new OpenCodeGoProviderCreator()
                .create(model, keyPoolFor(model), new HttpAiTransport());

        assertEquals("hello", provider.chat(request("hi", null, "conversation-7")));

        // 会话头不是鉴权头：鉴权仍是普通 Bearer
        assertEquals("Bearer oc_sk_demo", authHeaders.get(0));
        // 两个会话头必须一致，且是上游期望的 ses_ 形状
        assertEquals(sessionHeaders.get(0), sessionIdHeaders.get(0));
        assertTrue(sessionHeaders.get(0).matches("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$"),
                sessionHeaders.get(0));
        // 请求体多两个字段：官方分组键 + 流式回 usage
        assertTrue(lastBody.get().contains("\"include_usage\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"prompt_cache_key\":\"" + sessionHeaders.get(0) + "\""),
                lastBody.get());
    }

    @Test
    void extraHeadersFromConfigAreSentVerbatim() {
        Model model = baseModel(List.of("sk-a"));
        model.setHeaders(java.util.Map.of("x-tenant", "cw-1"));
        OpenAiCompatibleProvider provider = providerOf(model);

        assertEquals("hello", provider.chat(request("hi", null, null)));
        assertTrue(lastBody.get().contains("\"model\":\"glm-4-flash\""));
    }

    /** 直接驱动 Provider 的请求：模型覆盖与会话标识显式给定。 */
    private static AiRequest request(String prompt, String modelOverride, String sessionRef) {
        return new AiRequest(prompt, 1024, new AiTarget.PlatformModel(), modelOverride, sessionRef);
    }

    private OpenAiCompatibleProvider providerOf(Model model) {
        return (OpenAiCompatibleProvider) new OpenAiProviderCreator()
                .create(model, keyPoolFor(model), new HttpAiTransport());
    }

    private PlatformKeyPool keyPoolFor(Model model) {
        PlatformKeyPool keyPool = new PlatformKeyPool();
        // key 池按「端点身份」注册：ai.models[].name（本测试里是 GLM），不是模型名
        keyPool.register(model.getName(), model.getApiKeys());
        return keyPool;
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

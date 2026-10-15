package org.example.serviceai.ai.pool;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.ai.key.AiKeyPool;
import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.AiTarget;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.OpenCodeGoProviderCreator;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配工厂按 {@code ai.models[].type} 分派：缺省 openai（兼容线上没写 type 的存量配置）、
 * subscription 走专属鉴权头、未知 type 只跳过自己、key 注册进池。
 *
 * <p>这些用例直接对着 mock 端点断言「什么头上了线」，而不是断言内部对象类型——
 * 鉴权方式错了只有真实的线上请求才能发现。</p>
 */
class AiServiceFactoryTest {

    private HttpServer server;
    private String endpoint;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sessionHeaders = new CopyOnWriteArrayList<>();
    private final List<String> tenantHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sessionIdHeaders = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("x-opencode-session"));
            tenantHeaders.add(exchange.getRequestHeaders().getFirst("x-tenant"));
            sessionIdHeaders.add(exchange.getRequestHeaders().getFirst("x-opencode-session-id"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] payload = ("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
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
    void unsetTypeFallsBackToOpenAiBearer() {
        Model model = model("GLM", "glm-4-flash", "sk-a");
        // 刻意不设置 type：线上 Nacos 配置就是这样，必须继续按 openai 走
        AiServiceFactory factory = factoryOf(configOf(model));

        List<AiProvider> providers = factory.createConfiguredProviders();

        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).chat(request("hi", null, null))).isEqualTo("ok");
        assertThat(authHeaders.get(0)).isEqualTo("Bearer sk-a");
        assertThat(sessionHeaders.get(0)).isNull();
        assertThat(lastBody.get()).contains("\"model\":\"glm-4-flash\"");
    }

    @Test
    void openCodeGoKeepsBearerAuthAndAddsSessionHeaders() {
        Model model = model("ocgw", "deepseek-v4.1-flash", "oc_sk_demo");
        model.setType(OpenCodeGoProviderCreator.TYPE);
        AiServiceFactory factory = factoryOf(configOf(model));

        AiProvider provider = factory.createConfiguredProviders().get(0);
        assertThat(provider.chat(request("hi", null, "conversation-42"))).isEqualTo("ok");

        // 会话头不是鉴权头：鉴权仍是普通 Bearer
        assertThat(authHeaders.get(0)).isEqualTo("Bearer oc_sk_demo");
        // 上游要求 x-opencode-session，且与 x-opencode-session-id 同时存在时必须相等
        assertThat(sessionHeaders.get(0)).isNotNull();
        assertThat(sessionHeaders.get(0)).isEqualTo(sessionIdHeaders.get(0));
        assertThat(sessionHeaders.get(0)).matches("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$");
    }

    @Test
    void openCodeGoSessionIdIsStableForTheSameConversationAndDiffersPerConversation() {
        Model model = model("ocgw", "deepseek-v4.1-flash", "oc_sk_demo");
        model.setType(OpenCodeGoProviderCreator.TYPE);
        AiProvider provider = factoryOf(configOf(model)).createConfiguredProviders().get(0);

        provider.chat(request("hi", null, "conversation-42"));
        provider.chat(request("hi again", null, "conversation-42"));
        String first = sessionHeaders.get(0);
        String second = sessionHeaders.get(1);

        provider.chat(request("hi", null, "conversation-43"));
        String other = sessionHeaders.get(2);

        // 同一会话跨轮必须稳定（否则上游 prompt cache 永不命中），换会话必须换 id
        assertThat(first).isEqualTo(second);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void openCodeGoRequestBodyCarriesCacheKeyAndUsageOption() {
        Model model = model("ocgw", "deepseek-v4.1-flash", "oc_sk_demo");
        model.setType(OpenCodeGoProviderCreator.TYPE);
        AiProvider provider = factoryOf(configOf(model)).createConfiguredProviders().get(0);

        provider.chat(request("hi", null, "conversation-42"));

        // 官方分组键取与头相同的会话 id；流式要 usage 必须开口要 include_usage
        assertThat(lastBody.get()).contains("\"prompt_cache_key\":\"" + sessionHeaders.get(0) + "\"");
        assertThat(lastBody.get()).contains("\"include_usage\":true");
        assertThat(lastBody.get()).contains("\"stream\":true");
    }

    @Test
    void openAiTypeRequestBodyStaysFreeOfVendorFields() {
        Model model = model("GLM", "glm-4-flash", "sk-a");
        AiProvider provider = factoryOf(configOf(model)).createConfiguredProviders().get(0);

        provider.chat(request("hi", null, "conversation-42"));

        // 通用 OpenAI 端点不塞任何厂商私有字段：不认识它们的上游可能直接报错
        assertThat(lastBody.get()).doesNotContain("prompt_cache_key");
        assertThat(lastBody.get()).doesNotContain("include_usage");
        assertThat(sessionHeaders.get(0)).isNull();
        assertThat(authHeaders.get(0)).isEqualTo("Bearer sk-a");
    }

    @Test
    void openCodeGoWithoutSessionRefOmitsTheHeaderInsteadOfFakingOne() {
        Model model = model("ocgw", "deepseek-v4.1-flash", "oc_sk_demo");
        model.setType(OpenCodeGoProviderCreator.TYPE);
        AiProvider provider = factoryOf(configOf(model)).createConfiguredProviders().get(0);

        provider.chat(request("hi", null, null));

        // 省略而不是伪造：发占位值会静默污染上游缓存，比缺头（明确 400）更难查
        assertThat(sessionHeaders.get(0)).isNull();
        assertThat(lastBody.get()).doesNotContain("prompt_cache_key");
    }

    @Test
    void unknownTypeIsSkippedAndHealthyEntriesSurvive() {
        Model good = model("GLM", "glm-4-flash", "sk-a");
        Model broken = model("typo", "broken", "sk-b");
        broken.setType("no-such-protocol");
        AiServiceFactory factory = factoryOf(configOf(good, broken));

        List<AiProvider> providers = factory.createConfiguredProviders();

        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).name()).isEqualTo("GLM");
    }

    @Test
    void keysAreRegisteredIntoThePoolPerEndpoint() {
        Model model = model("GLM", "glm-4-flash", "sk-a", "sk-b");
        AiKeyPool keyPool = new PlatformKeyPool();
        factoryOf(configOf(model), keyPool).createConfiguredProviders();

        assertThat(keyPool.nextKey("GLM")).isIn("sk-a", "sk-b");
        // 没被配置的端点取 key 一律报密钥无效，而不是返回 null 让请求裸奔
        assertThatThrownBy(() -> keyPool.nextKey("never-configured"))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.PROVIDER_KEY_INVALID.getCode());
    }

    @Test
    void extraHeadersFromConfigReachTheWire() {
        Model model = model("GLM", "glm-4-flash", "sk-a");
        model.setHeaders(Map.of("x-tenant", "cw-1"));
        AiServiceFactory factory = factoryOf(configOf(model));

        factory.createConfiguredProviders().get(0).chat(request("hi", null, null));

        assertThat(tenantHeaders.get(0)).isEqualTo("cw-1");
    }

    @Test
    void emptyModelListYieldsNoProviders() {
        AiConfig config = new AiConfig();
        config.setModels(List.of());

        assertThat(factoryOf(config).createConfiguredProviders()).isEmpty();

        config.setModels(null);
        assertThat(factoryOf(config).createConfiguredProviders()).isEmpty();
    }

    @Test
    void userConfigProviderUsesGivenEndpointKeyAndModel() {
        AiServiceFactory factory = factoryOf(new AiConfig());

        AiProvider provider = factory.createUserConfigProvider(
                7L, endpoint, "user-key", "user-model", Duration.ofSeconds(30));

        assertThat(provider.chat(request("hi", null, null))).isEqualTo("ok");
        assertThat(authHeaders.get(0)).isEqualTo("Bearer user-key");
        assertThat(lastBody.get()).contains("\"model\":\"user-model\"");
        // 用户端点不进池、不参与轮转
        assertThat(provider.name()).isEqualTo("user-config-7:user-model");
    }

    /**
     * 端点身份取 {@code ai.models[].name}，不取默认模型名——两个端点的默认模型同名时，
     * 身份必须仍然不同。
     *
     * <p>改造前 key 池是按默认模型名注册的，这种配置下后注册的会覆盖前者的 key 列表，
     * 于是 A 端点的地址会配上 B 端点的密钥。这条钉住的就是那个缺陷。</p>
     */
    @Test
    void endpointIdentityComesFromNameSoKeyPoolsStaySeparate() {
        Model a = model("identity-a", "shared-model", "sk-a");
        Model b = model("identity-b", "shared-model", "sk-b");
        AiKeyPool keyPool = new PlatformKeyPool();

        List<AiProvider> providers = factoryOf(configOf(a, b), keyPool).createConfiguredProviders();

        assertThat(providers).extracting(AiProvider::name)
                .containsExactly("identity-a", "identity-b");
        assertThat(keyPool.nextKey("identity-a")).isEqualTo("sk-a");
        assertThat(keyPool.nextKey("identity-b")).isEqualTo("sk-b");
    }

    @Test
    void servedModelsAreModelNamePlusDefaultModel() {
        Model model = model("GLM", "glm-4-flash", "sk-a");
        model.setModelName(List.of("glm-4-air", "glm-4-plus"));

        List<AiProvider> providers = factoryOf(configOf(model)).createConfiguredProviders();

        // 默认模型并进集合，保证 AUTO 正在用的模型在模型选择列表里也选得到
        assertThat(providers.get(0).servedModels())
                .containsExactlyInAnyOrder("glm-4-air", "glm-4-plus", "glm-4-flash");
    }

    /** 直接驱动 Provider 的请求：模型覆盖与会话标识显式给定，协议据此决定送什么。 */
    private static AiRequest request(String prompt, String modelOverride, String sessionRef) {
        return new AiRequest(prompt, 1024, new AiTarget.PlatformModel(), modelOverride, sessionRef);
    }

    private static AiServiceFactory factoryOf(AiConfig config) {
        return factoryOf(config, new PlatformKeyPool());
    }

    private static AiServiceFactory factoryOf(AiConfig config, AiKeyPool keyPool) {
        ObjectProvider<AiProvider> beans =
                new DefaultListableBeanFactory().getBeanProvider(AiProvider.class);
        return new AiServiceFactory(
                config,
                keyPool,
                new HttpAiTransport(),
                List.of(new OpenAiProviderCreator(), new OpenCodeGoProviderCreator()),
                beans
        );
    }

    private static AiConfig configOf(Model... models) {
        AiConfig config = new AiConfig();
        config.setHealthCheckEnabled(false);
        config.setModels(List.of(models));
        return config;
    }

    private Model model(String name, String defaultModel, String... apiKeys) {
        Model model = new Model();
        model.setName(name);
        model.setUrl(endpoint);
        model.setModelName(List.of(defaultModel));
        model.setDefaultModelName(defaultModel);
        model.setApiKeys(List.of(apiKeys));
        model.setTimeout(5000);
        return model;
    }
}

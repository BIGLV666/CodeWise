package org.example.serviceai.ai.pool;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.ai.key.PlatformKeyPool;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.AiTarget;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.provider.OpenAiProviderCreator;
import org.example.serviceai.ai.provider.OpenCodeGoProviderCreator;
import org.example.serviceai.ai.transport.HttpAiTransport;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压测：1) 真实 HTTP 路径并发借端点 + 调用，验证参数不串、无连接异常、无竞态；
 * 2) 状态机热路径（borrowAuto/reportSuccess/reportFailure/switchToNext/switchTo/status）
 * 高并发混跑，验证无异常、无死锁。
 *
 * <p>mock 端点会把请求体里的 model 原样回显，因此「响应内容 == 请求的 model」即证明
 * 并发下模型参数没有被其他线程串改。</p>
 */
class DefaultAiPoolConcurrencyTest {

    private static final int CALL_THREADS = 32;
    private static final int CALLS_PER_THREAD = 100;
    private static final int HOT_THREADS = 16;
    private static final int HOT_ITERATIONS = 200_000;
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void startEchoServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/v1/chat/completions", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String model = extractModel(body);
            byte[] payload = ("data: {\"choices\":[{\"delta\":{\"content\":\"" + model + "\"}}]}\n\n"
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
    void concurrentCallsKeepModelParamsIsolated() throws Exception {
        DefaultAiPool pool = poolOf();
        List<String> models = List.of("m-1", "m-2", "m-3", "m-4");
        warmUp(() -> pool.borrowAuto().chat(request("ping", "m-1", DEFAULT_MAX_TOKENS)));
        List<String> identities = List.of("P1", "P2", "P3", "P4");

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalLatencyNanos = new AtomicLong();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(CALL_THREADS);

        for (int t = 0; t < CALL_THREADS; t++) {
            int worker = t;
            workers.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        String expected = models.get((worker + i) % models.size());
                        AiProvider provider = pool.borrowAuto();
                        assertThat(provider).isNotNull();
                        long begin = System.nanoTime();
                        String answer = provider.chat(request("ping", expected, DEFAULT_MAX_TOKENS));
                        long elapsed = System.nanoTime() - begin;
                        totalLatencyNanos.addAndGet(elapsed);
                        latencies.add(elapsed);
                        if (!expected.equals(answer)) {
                            failures.add(new AssertionError(
                                    "模型参数串了：期望 " + expected + " 实际 " + answer));
                            return;
                        }
                        pool.reportSuccess(provider);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        long startMillis = System.currentTimeMillis();
        start.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        assertThat(failures).as("并发调用出现异常: %s", failures).isEmpty();
        printStats("HTTP 并发调用", CALL_THREADS * CALLS_PER_THREAD, elapsedMillis, totalLatencyNanos, latencies);
    }

    @Test
    void poolStateMachineSurvivesHotConcurrentAccess() throws Exception {
        DefaultAiPool pool = poolOf();
        List<String> models = List.of("m-1", "m-2", "m-3", "m-4");
        List<String> identities = List.of("P1", "P2", "P3", "P4");

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(HOT_THREADS);

        for (int t = 0; t < HOT_THREADS; t++) {
            int worker = t;
            workers.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < HOT_ITERATIONS; i++) {
                        switch ((worker + i) % 5) {
                            case 0 -> pool.borrowAuto();
                            case 1 -> pool.switchToNext();
                            case 2 -> recordFailure(pool);
                            case 3 -> recordSuccess(pool);
                            default -> {
                                pool.switchTo(identities.get(i % identities.size()));
                                pool.status();
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        long startMillis = System.currentTimeMillis();
        start.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        assertThat(failures).as("状态机高并发出现异常: %s", failures).isEmpty();
        long operations = (long) HOT_THREADS * HOT_ITERATIONS;
        System.err.printf("RESULT 状态机热路径: %,d ops in %,d ms -> %,d ops/s%n",
                operations, elapsedMillis, operations * 1000 / Math.max(1, elapsedMillis));
    }

    /**
     * 指定模型路径的并发压测：多个端点声明同一个模型时，按模型借用要能在它们之间散开，
     * 且请求里的模型名不会被别的线程串改（mock 端点会把请求里的 model 原样回显）。
     */
    @Test
    void concurrentModelRoutingStaysIsolatedAndSpreadsAcrossEndpoints() throws Exception {
        String sharedModel = "shared-model";
        List<String> sharedModels = List.of(sharedModel);
        DefaultAiPool pool = poolOf(sharedModels, 4);
        warmUp(() -> pool.borrowForModel(sharedModel).chat(request("ping", sharedModel, DEFAULT_MAX_TOKENS)));

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Map<String, AtomicInteger> usage = new ConcurrentHashMap<>();
        AtomicLong totalLatencyNanos = new AtomicLong();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(CALL_THREADS);

        for (int t = 0; t < CALL_THREADS; t++) {
            workers.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        AiProvider provider = pool.borrowForModel(sharedModel);
                        long begin = System.nanoTime();
                        String answer = provider.chat(request("ping", sharedModel, DEFAULT_MAX_TOKENS));
                        long elapsed = System.nanoTime() - begin;
                        totalLatencyNanos.addAndGet(elapsed);
                        latencies.add(elapsed);
                        if (!sharedModel.equals(answer)) {
                            failures.add(new AssertionError(
                                    "模型参数串了：期望 " + sharedModel + " 实际 " + answer));
                            return;
                        }
                        usage.computeIfAbsent(provider.name(), key -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        long startMillis = System.currentTimeMillis();
        start.countDown();
        workers.shutdown();
        assertThat(workers.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        assertThat(failures).as("按模型并发调用出现异常: %s", failures).isEmpty();
        // 同模型多端点：随机取应当覆盖全部候选，否则负载均衡名不副实
        assertThat(usage.keySet()).containsExactlyInAnyOrder("P1", "P2", "P3", "P4");
        printStats("指定模型并发调用", CALL_THREADS * CALLS_PER_THREAD, elapsedMillis, totalLatencyNanos, latencies);
    }

    /**
     * 预热：类里第一个跑的测量必然带着类加载与 JIT 成本，P99 会出现与并发度无关的胖尾
     * （表现为第一个档位 P99 是后面档位的数倍）。先跑若干次不计入统计，各档位才可比。
     */
    private static void warmUp(Runnable call) {
        for (int i = 0; i < 300; i++) {
            call.run();
        }
    }

    /** 降级状态下 borrowAuto() 返回 null，此时没有可记账的对象，直接跳过。 */
    private static void recordFailure(DefaultAiPool pool) {
        AiProvider provider = pool.borrowAuto();
        if (provider != null) {
            pool.reportFailure(provider);
        }
    }

    private static void recordSuccess(DefaultAiPool pool) {
        AiProvider provider = pool.borrowAuto();
        if (provider != null) {
            pool.reportSuccess(provider);
        }
    }

    /** 直接驱动 Provider 的请求：mock 端点会把请求里的 model 原样回显，用来验证参数没串。 */
    private static AiRequest request(String prompt, String modelOverride, int maxTokens) {
        return new AiRequest(prompt, maxTokens, new AiTarget.PlatformModel(), modelOverride, null);
    }

    private DefaultAiPool poolOf() {
        return poolOf(null, 4);
    }

    /**
     * {@code declaredModels} 非空时所有端点都声明同一批模型——用于验证「同一模型被多个端点
     * 声明时，按模型借用要能散开」。
     */
    private DefaultAiPool poolOf(List<String> declaredModels, int count) {
        AiConfig config = new AiConfig();
        config.setHealthCheckEnabled(false);
        List<Model> models = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Model model = new Model();
            model.setName("P" + i);
            model.setUrl(endpoint);
            model.setModelName(declaredModels == null ? List.of("m-" + i) : declaredModels);
            model.setDefaultModelName(declaredModels == null ? "m-" + i : declaredModels.get(0));
            model.setApiKeys(List.of("sk-1", "sk-2", "sk-3"));
            model.setPriority(i);
            model.setEnabled(true);
            model.setTimeout(10_000);
            models.add(model);
        }
        config.setModels(models);

        ObjectProvider<AiProvider> beans =
                new DefaultListableBeanFactory().getBeanProvider(AiProvider.class);
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

    private static String extractModel(String body) {
        int key = body.indexOf("\"model\":\"");
        if (key < 0) {
            return "unknown";
        }
        int from = key + "\"model\":\"".length();
        int to = body.indexOf('"', from);
        return to < 0 ? "unknown" : body.substring(from, to);
    }

    private static void printStats(
            String label,
            long requests,
            long elapsedMillis,
            AtomicLong totalLatencyNanos,
            List<Long> latencies
    ) {
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2);
        long p99 = sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99)));
        System.err.printf(
                "RESULT %s: %d 次 / %d ms -> %.1f req/s, 平均 %.2f ms, P50 %.2f ms, P99 %.2f ms%n",
                label,
                requests,
                elapsedMillis,
                requests * 1000.0 / Math.max(1, elapsedMillis),
                totalLatencyNanos.get() / 1_000_000.0 / Math.max(1, requests),
                p50 / 1_000_000.0,
                p99 / 1_000_000.0
        );
    }
}

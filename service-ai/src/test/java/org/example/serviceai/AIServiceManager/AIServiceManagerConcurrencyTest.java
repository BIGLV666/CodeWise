package org.example.serviceai.AIServiceManager;

import com.sun.net.httpserver.HttpServer;
import org.example.serviceai.config.AiConfig;
import org.example.serviceai.entry.Model;
import org.example.serviceai.intifer.CallAi;
import org.example.serviceai.util.GetAPiKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压测：1) 真实 HTTP 路径并发拿服务 + 调用，验证参数不串、无连接异常、无竞态；
 * 2) 状态机热路径（getAvailableService/recordSuccess/recordFailure/switchToService/
 * getAllServiceStatus）高并发混跑，验证无异常、无死锁。
 *
 * <p>mock 端点会把请求体里的 model 原样回显，因此「响应内容 == 请求的 model」即证明
 * 并发下模型参数没有被其他线程串改。</p>
 */
class AIServiceManagerConcurrencyTest {

    private static final int CALL_THREADS = 32;
    private static final int CALLS_PER_THREAD = 100;
    private static final int HOT_THREADS = 16;
    private static final int HOT_ITERATIONS = 200_000;

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
        AIServiceManager manager = managerOf();
        List<String> models = List.of("m-1", "m-2", "m-3", "m-4");

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalLatencyNanos = new AtomicLong();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CALL_THREADS);

        for (int t = 0; t < CALL_THREADS; t++) {
            int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        String expected = models.get((worker + i) % models.size());
                        CallAi service = manager.getAvailableService();
                        assertThat(service).isNotNull();
                        long begin = System.nanoTime();
                        String answer = service.callAi("ping", expected);
                        long elapsed = System.nanoTime() - begin;
                        totalLatencyNanos.addAndGet(elapsed);
                        latencies.add(elapsed);
                        if (!expected.equals(answer)) {
                            failures.add(new AssertionError(
                                    "模型参数串了：期望 " + expected + " 实际 " + answer));
                            return;
                        }
                        manager.recordSuccess(service);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        long startMillis = System.currentTimeMillis();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        assertThat(failures).as("并发调用出现异常: %s", failures).isEmpty();
        printStats("HTTP 并发调用", CALL_THREADS * CALLS_PER_THREAD, elapsedMillis, totalLatencyNanos, latencies);
    }

    @Test
    void managerStateMachineSurvivesHotConcurrentAccess() throws Exception {
        AIServiceManager manager = managerOf();
        List<String> models = List.of("m-1", "m-2", "m-3", "m-4");

        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(HOT_THREADS);

        for (int t = 0; t < HOT_THREADS; t++) {
            int worker = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < HOT_ITERATIONS; i++) {
                        switch ((worker + i) % 5) {
                            case 0 -> manager.getAvailableService();
                            case 1 -> manager.switchToNext();
                            case 2 -> recordFailure(manager);
                            case 3 -> recordSuccess(manager);
                            default -> {
                                manager.switchToService(models.get(i % models.size()));
                                manager.getAllServiceStatus();
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
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        assertThat(failures).as("状态机高并发出现异常: %s", failures).isEmpty();
        long operations = (long) HOT_THREADS * HOT_ITERATIONS;
        System.err.printf("RESULT 状态机热路径: %,d ops in %,d ms -> %,d ops/s%n",
                operations, elapsedMillis, operations * 1000 / Math.max(1, elapsedMillis));
    }

    /** 降级状态下 getAvailableService() 返回 null，此时没有可记账的对象，直接跳过。 */
    private static void recordFailure(AIServiceManager manager) {
        CallAi service = manager.getAvailableService();
        if (service != null) {
            manager.recordFailure(service);
        }
    }

    private static void recordSuccess(AIServiceManager manager) {
        CallAi service = manager.getAvailableService();
        if (service != null) {
            manager.recordSuccess(service);
        }
    }

    private AIServiceManager managerOf() {        AiConfig config = new AiConfig();
        config.setHealthCheckEnabled(false);
        List<Model> models = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Model model = new Model();
            model.setName("P" + i);
            model.setUrl(endpoint);
            model.setModelName(List.of("m-" + i));
            model.setDefaultModelName("m-" + i);
            model.setApiKeys(List.of("sk-1", "sk-2", "sk-3"));
            model.setPriority(i);
            model.setEnabled(true);
            model.setTimeout(10_000);
            models.add(model);
        }
        config.setModels(models);

        AIServiceManager manager = new AIServiceManager(config, new GetAPiKey());
        manager.init();
        return manager;
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

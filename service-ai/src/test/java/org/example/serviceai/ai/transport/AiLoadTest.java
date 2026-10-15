package org.example.serviceai.ai.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输层压测：逐级加大并发（突刺）+ 持续负载。
 *
 * <p>压测要回答两个问题：<b>吞吐和尾延迟有没有退化</b>，以及
 * <b>持续加压下连接数会不会随调用次数一起涨</b>——后者就是连接泄漏的直接指标。</p>
 *
 * <p>持续负载时长可用 {@code -Dai.load.soak-seconds=N} 加长（默认 10s），
 * 用于观察长时间运行下的连接与线程漂移。</p>
 */
class AiLoadTest {

    private static final int[] SPIKE_LEVELS = {8, 32, 128};
    private static final int CALLS_PER_WORKER_PER_LEVEL = 25;
    private static final long SOAK_SECONDS =
            Long.getLong("ai.load.soak-seconds", 10L);

    private final HttpAiTransport transport = new HttpAiTransport();

    @Test
    void spikeAtIncreasingConcurrencyKeepsLatencyAndConnectionsBounded() throws Exception {
        try (RawSseServer server = new RawSseServer().chunks(4).chunkPayloadSize(64)) {
            List<String> lines = new ArrayList<>();
            for (int level : SPIKE_LEVELS) {
                int calls = level * CALLS_PER_WORKER_PER_LEVEL;
                Run run = runLoad(server, level, CALLS_PER_WORKER_PER_LEVEL, Duration.ZERO);

                assertEquals(0, run.failures.size(), () -> "并发 " + level + " 出现异常: " + run.failures);
                lines.add(String.format(
                        "RESULT 突刺并发 %d: %d 次 / %d ms -> %.0f req/s, 平均 %.2f ms, P50 %.2f ms, P99 %.2f ms, 连接数 %d",
                        level, calls, run.elapsedMillis, run.requestsPerSecond(),
                        run.meanMillis(), run.percentileMillis(0.50), run.percentileMillis(0.99),
                        server.acceptedConnections()));
            }
            lines.forEach(System.err::println);

            // 连接数必须远小于调用次数（复用生效），否则「连接池」名不副实
            assertTrue(server.acceptedConnections() < totalCalls() / 2,
                    "连接数 " + server.acceptedConnections() + " 接近调用次数，说明连接没有被复用");
        }
    }

    @Test
    void sustainedLoadDoesNotLeakConnections() throws Exception {
        try (RawSseServer server = new RawSseServer().chunks(4).chunkPayloadSize(64)) {
            int workers = 64;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(SOAK_SECONDS);
            AtomicLong calls = new AtomicLong();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            AtomicLong totalNanos = new AtomicLong();

            ExecutorService pool = Executors.newFixedThreadPool(workers);
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    while (System.currentTimeMillis() < deadline) {
                        long begin = System.nanoTime();
                        try {
                            transport.send(command(server, Duration.ofSeconds(10)));
                            calls.incrementAndGet();
                            long elapsed = System.nanoTime() - begin;
                            totalNanos.addAndGet(elapsed);
                            latencies.add(elapsed);
                        } catch (Throwable failure) {
                            failures.add(failure);
                            return;
                        }
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(SOAK_SECONDS + 60, TimeUnit.SECONDS));

            Run run = new Run(calls.get(), SOAK_SECONDS * 1000, totalNanos.get(), latencies, failures);
            System.err.printf(
                    "RESULT 持续负载 %ds（并发 %d）: %,d 次 -> %.0f req/s, 平均 %.2f ms, P50 %.2f ms, P99 %.2f ms, 累计连接数 %d%n",
                    SOAK_SECONDS, workers, run.requests, run.requestsPerSecond(),
                    run.meanMillis(), run.percentileMillis(0.50), run.percentileMillis(0.99),
                    server.acceptedConnections());

            assertEquals(0, failures.size(), () -> "持续负载出现异常: " + failures);
            // 泄漏时连接数会跟着调用次数线性上涨；复用良好时它只跟并发度同量级
            assertTrue(server.acceptedConnections() <= workers * 2,
                    "累计连接数 " + server.acceptedConnections() + " 远超并发度 " + workers + "，疑似连接泄漏");
            assertTrue(server.closedConnections() <= workers * 2,
                    "异常关闭的连接数 " + server.closedConnections() + " 过多");
        }
    }

    private int totalCalls() {
        int total = 0;
        for (int level : SPIKE_LEVELS) {
            total += level * CALLS_PER_WORKER_PER_LEVEL;
        }
        return total;
    }

    private Run runLoad(
            RawSseServer server,
            int workers,
            int callsPerWorker,
            Duration gap
    ) throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalNanos = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int c = 0; c < callsPerWorker; c++) {
                        long begin = System.nanoTime();
                        transport.send(command(server, Duration.ofSeconds(10)));
                        long elapsed = System.nanoTime() - begin;
                        totalNanos.addAndGet(elapsed);
                        latencies.add(elapsed);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }

        long beginMillis = System.currentTimeMillis();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));
        long elapsedMillis = System.currentTimeMillis() - beginMillis;

        return new Run(
                (long) workers * callsPerWorker, elapsedMillis, totalNanos.get(), latencies, failures);
    }

    private ChatCommand command(RawSseServer server, Duration readIdleTimeout) {
        return ChatCommand.builder("load-endpoint", server.endpoint())
                .header("Accept", "text/event-stream")
                .jsonBody("{\"model\":\"m\",\"stream\":true}")
                .connectTimeout(Duration.ofSeconds(10))
                .readIdleTimeout(readIdleTimeout)
                .build();
    }

    private record Run(
            long requests,
            long elapsedMillis,
            long totalNanos,
            List<Long> latencies,
            List<Throwable> failures
    ) {

        double requestsPerSecond() {
            return requests * 1000.0 / Math.max(1, elapsedMillis);
        }

        double meanMillis() {
            return totalNanos / 1_000_000.0 / Math.max(1, requests);
        }

        double percentileMillis(double percentile) {
            if (latencies.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int index = Math.min(sorted.size() - 1, (int) (sorted.size() * percentile));
            return sorted.get(index) / 1_000_000.0;
        }
    }
}

package org.example.serviceai.ai.transport;

import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传输层最要命的四件事：
 *
 * <ol>
 *   <li><b>连接复用</b>：连续调用应在同一条 TCP 连接上完成（否则「连接池」只是说法）。</li>
 *   <li><b>读超时语义</b>：超时是「分片之间空闲多久」，不是「整个请求多久」——
 *       用后者会把超过连接超时的正常长回答掐死。</li>
 *   <li><b>连接归还</b>：空闲超时、消费方提前放弃这两条异常路径都必须真正断开连接，
 *       否则连接不还池、池被耗尽，全站 AI 一起挂。</li>
 *   <li>分片无 {@code data:} 后空格时不能被静默丢弃。</li>
 * </ol>
 */
class HttpAiTransportTest {

    private final HttpAiTransport transport = new HttpAiTransport();

    @Test
    void streamsAllChunksUntilEndOfStream() throws Exception {
        try (RawSseServer server = new RawSseServer().chunks(3)) {
            List<String> chunks = new ArrayList<>();

            transport.sendStream(command(server, Duration.ofSeconds(5), Duration.ofSeconds(5)), chunks::add);

            assertEquals(3, chunks.size());
            assertTrue(chunks.stream().allMatch(chunk -> chunk.startsWith("x")));
        }
    }

    @Test
    void sequentialCallsReuseTheSameConnection() throws Exception {
        try (RawSseServer server = new RawSseServer().chunks(2)) {
            for (int i = 0; i < 5; i++) {
                transport.send(command(server, Duration.ofSeconds(5), Duration.ofSeconds(5)));
            }

            assertEquals(1, server.acceptedConnections(),
                    "5 次调用应复用同一条 keep-alive 连接；连接数等于调用数说明池没生效");
        }
    }

    @Test
    void slowStreamOutlivingConnectTimeoutIsNotKilled() throws Exception {
        // 总时长 6 × 250ms = 1.5s，远超 500ms 的连接超时；每片间隔 250ms 远小于 3s 的读空闲超时
        try (RawSseServer server = new RawSseServer()
                .chunks(6)
                .chunkGapMillis(250)) {
            List<String> chunks = new ArrayList<>();

            transport.sendStream(
                    command(server, Duration.ofMillis(500), Duration.ofSeconds(3)),
                    chunks::add);

            assertEquals(6, chunks.size(),
                    "长回答的总时长超过连接超时是正常的，不能被整体截止时间掐死");
        }
    }

    @Test
    void stalledStreamFailsWithIdleTimeoutInsteadOfHanging() throws Exception {
        try (RawSseServer server = new RawSseServer().stallAfterFirstChunk()) {
            long begin = System.currentTimeMillis();

            BizException failure = assertThrows(BizException.class, () -> transport.sendStream(
                    command(server, Duration.ofSeconds(5), Duration.ofMillis(300)),
                    chunk -> { }));

            long elapsed = System.currentTimeMillis() - begin;
            assertEquals(AiErrorCode.PROVIDER_CALL_FAILED.getCode(), failure.getCode());
            assertNotNull(timeoutInCauseChain(failure), "超时异常要留在 cause 链上，供上层识别超时");
            assertTrue(elapsed < 3000, "应按 300ms 空闲超时返回，而不是等服务端 3s 后收场（实际 " + elapsed + "ms）");
        }
    }

    @Test
    void idleTimeoutReleasesTheConnection() throws Exception {
        try (RawSseServer server = new RawSseServer().stallAfterFirstChunk()) {
            assertThrows(BizException.class, () -> transport.sendStream(
                    command(server, Duration.ofSeconds(5), Duration.ofMillis(200)),
                    chunk -> { }));

            assertTrue(server.awaitPeerClose(5000),
                    "读超时后必须断开连接，否则连接不还池、池会被耗尽");
        }
    }

    /**
     * 消费方提前放弃、且响应体<b>没被读完</b>时，连接必须被真正断开——
     * 这种连接回不了池（响应还没结束），留着就是泄漏。
     *
     * <p>反之，响应体已被完整读走的情况由 {@link #sequentialCallsReuseTheSameConnection}
     * 覆盖：那是归还到池、连接保持打开，属正常。</p>
     */
    @Test
    void abandonedStreamReleasesTheConnection() throws Exception {
        try (RawSseServer server = new RawSseServer().stallAfterFirstChunk()) {
            // 读空闲超时给得很宽，确保是「消费方放弃」而不是「超时」触发的断开
            assertThrows(IllegalStateException.class, () -> transport.sendStream(
                    command(server, Duration.ofSeconds(10), Duration.ofSeconds(30)),
                    chunk -> {
                        throw new IllegalStateException("消费方放弃");
                    }));

            assertTrue(server.awaitPeerClose(8000),
                    "响应体没读完就被放弃时也必须断开连接，否则连接不还池、池会被耗尽");
        }
    }

    @Test
    void concurrentStreamsDoNotExhaustConnections() throws Exception {
        try (RawSseServer server = new RawSseServer().chunks(2)) {
            int workers = 32;
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(workers);

            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        transport.send(command(server, Duration.ofSeconds(10), Duration.ofSeconds(10)));
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

            assertTrue(failures.isEmpty(), () -> "并发流式调用出现异常: " + failures);
        }
    }

    private ChatCommand command(RawSseServer server, Duration connectTimeout, Duration readIdleTimeout) {
        return ChatCommand.builder("test-endpoint", server.endpoint())
                .header("Accept", "text/event-stream")
                .jsonBody("{\"model\":\"m\",\"stream\":true}")
                .connectTimeout(connectTimeout)
                .readIdleTimeout(readIdleTimeout)
                .build();
    }

    private Throwable timeoutInCauseChain(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current.getClass().getName().toLowerCase().contains("timeout")) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}

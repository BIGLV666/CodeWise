package org.example.serviceai.ai.transport;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link AiTransport} 的 HTTP 实现：单例 {@link HttpClient} 自带 keep-alive 连接池，
 * 跨请求复用 TCP/TLS 连接（此前每次调用都 {@code new URL().openConnection()}，握手开销每请求重付）。
 *
 * <p><b>为什么不用 {@code HttpRequest.timeout()}：</b>它是「整个请求（含读完 body）」的截止时间，
 * 而 {@code HttpURLConnection.setReadTimeout} 是「两次读之间」的空闲超时。SSE 长回答动辄几分钟，
 * 直接用前者会把正常的流式输出掐死。这里只设连接超时，读超时由 {@link #pump} 按
 * 「chunk 之间空闲多久」判定，语义与改造前一致。</p>
 *
 * <p><b>连接必须归还：</b>流式响应不读到 EOF 又不关闭，连接就不回池，池会被耗尽。
 * 因此响应体在所有出口（正常结束、空闲超时、业务异常、客户端放弃）都被显式关闭。</p>
 */
@Component
@Slf4j
public class HttpAiTransport implements AiTransport {

    private static final int MAX_ERROR_BODY_LENGTH = 1000;
    /** 读循环结束哨兵：与任何真实 SSE 行都不可能相等。 */
    private static final String END_OF_STREAM = "\u0000ai-transport-eof";

    /** 按连接超时缓存客户端：HttpClient 的连接超时只能在构建时设定，无法按请求覆盖。 */
    private final Map<Duration, HttpClient> clients = new ConcurrentHashMap<>();

    @Override
    public void sendStream(ChatCommand command, Consumer<String> onChunk) {
        HttpResponse<InputStream> response;
        try {
            response = client(command.connectTimeout())
                    .send(toRequest(command), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw unreachable(command, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unreachable(command, exception);
        }

        try (InputStream body = response.body()) {
            if (isRedirect(response.statusCode())) {
                throw redirectRejected(command, response);
            }
            if (!isSuccess(response.statusCode())) {
                throw rejected(command, response, body);
            }
            pump(command, body, onChunk);
        } catch (IOException exception) {
            throw unreachable(command, exception);
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    /**
     * 重定向一律拒绝，绝不自动跟随。
     *
     * <p>跟随到哪由上游决定：JDK HttpClient 默认是 {@code Redirect.NORMAL}，
     * 自动跟随会让地址校验（含用户端点的内网拦截）形同虚设。这里的语义与
     * 改造前一致——重定向说明配置的地址不能直连，属配置错误而非服务繁忙，
     * 因此给 4xx 类错误，让调用方不要盲目重试。</p>
     */
    private BizException redirectRejected(ChatCommand command, HttpResponse<InputStream> response) {
        String location = response.headers().firstValue("Location").orElse("(none)");
        log.warn("{} endpoint redirected to {}, rejected (status={})",
                command.providerName(), location, response.statusCode());
        return new BizException(
                AiErrorCode.AI_URL_INVALID,
                new IllegalStateException(command.providerName() + " returned HTTP "
                        + response.statusCode() + ", location=" + location)
        );
    }

    private HttpRequest toRequest(ChatCommand command) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(command.endpointUrl()))
                .POST(HttpRequest.BodyPublishers.ofString(command.jsonBody(), StandardCharsets.UTF_8));
        // Provider 组装的认证头（Authorization / x-opencode-session 等）一律原样下发
        command.headers().forEach(builder::header);
        if (command.headers().keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }
        return builder.build();
    }

    private HttpClient client(Duration connectTimeout) {
        return clients.computeIfAbsent(connectTimeout, timeout -> HttpClient.newBuilder()
                // 与改造前的 HttpURLConnection 一致：SSE 网关普遍只吃 HTTP/1.1，keep-alive 复用池仍生效
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                // JDK 默认是 Redirect.NORMAL（自动跟随）；自动跟随会绕过地址校验，必须显式关掉
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * 逐行读取 SSE 并按「chunk 间空闲超时」判失败。
     *
     * <p>用一个虚拟线程把阻塞读推给队列，调用线程只做 {@code poll(idleTimeout)}，
     * 这样既能拿到真正的空闲超时，又不改变「回调在调用线程上按序执行」的语义。</p>
     */
    private void pump(ChatCommand command, InputStream body, Consumer<String> onChunk) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = Thread.ofVirtual().name("ai-sse-reader").start(() -> {
            try (BufferedReader buffered = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = buffered.readLine()) != null) {
                    lines.put(line);
                }
            } catch (Exception closed) {
                log.debug("{} SSE read ended: {}", command.providerName(), closed.toString());
            } finally {
                // 队列无界，offer 不会阻塞也不会抛 InterruptedException
                lines.offer(END_OF_STREAM);
            }
        });

        try {
            while (true) {
                String line = lines.poll(command.readIdleTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (line == null) {
                    throw idleTimeout(command);
                }
                if (END_OF_STREAM.equals(line)) {
                    return;
                }
                String content = SseParser.parseChunk(command.providerName(), line);
                if (content != null) {
                    onChunk.accept(content);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(AiErrorCode.PROVIDER_CALL_FAILED, interrupted);
        } finally {
            // 提前返回（空闲超时/业务异常）时立刻断流，不等调用方跑到 try-with-resources 才归还连接
            closeQuietly(body);
            reader.interrupt();
        }
    }

    /** 用 SocketTimeoutException 作 cause：上游按类名含 timeout 识别超时，与改造前一致。 */
    private BizException idleTimeout(ChatCommand command) {
        SocketTimeoutException timeout = new SocketTimeoutException(
                command.providerName() + " 读取超时（" + command.readIdleTimeout().toMillis() + "ms 无数据）");
        log.warn("{} read idle timeout after {}ms", command.providerName(), command.readIdleTimeout().toMillis());
        return new BizException(AiErrorCode.PROVIDER_CALL_FAILED, timeout);
    }

    private AiProviderHttpException rejected(
            ChatCommand command,
            HttpResponse<InputStream> response,
            InputStream body
    ) {
        AiProviderHttpException rejection = new AiProviderHttpException(
                command.providerName(),
                response.statusCode(),
                readErrorBody(body),
                response.headers().firstValue("Retry-After").orElse(null)
        );
        log.warn("{} API request rejected: {}", command.providerName(), rejection.getDiagnostic());
        return rejection;
    }

    private BizException unreachable(ChatCommand command, Exception cause) {
        log.warn("{} endpoint unreachable: {}", command.providerName(), cause.toString());
        return new BizException(AiErrorCode.PROVIDER_CALL_FAILED, cause);
    }

    private String readErrorBody(InputStream errorStream) {
        try {
            String body = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            return body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_LENGTH));
        } catch (IOException exception) {
            log.debug("error body unreadable: {}", exception.toString());
            return "";
        }
    }

    private void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException exception) {
            log.debug("stream close failed: {}", exception.toString());
        }
    }

    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}

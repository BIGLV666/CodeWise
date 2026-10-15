package org.example.serviceai.ai.transport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简的 SSE 测试服务端，跑在裸 {@link ServerSocket} 上，用来观察 TCP 层面的行为——
 * 「连接有没有被复用」「提前中断后客户端有没有关连接」这两件事，
 * {@code com.sun.net.httpserver.HttpServer} 都不暴露。
 *
 * <p>支持 HTTP/1.1 keep-alive：在同一个连接上循环处理请求，因此
 * {@link #acceptedConnections()} 直接反映客户端是否复用了连接。</p>
 */
final class RawSseServer implements AutoCloseable {

    /** 返回第一个分片后就不再发数据、也不关连接，用于触发读空闲超时。 */
    private static final long STALL_MILLIS = 3000L;

    private final ServerSocket serverSocket;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger peerClosed = new AtomicInteger();
    private final CountDownLatch firstPeerClose = new CountDownLatch(1);

    private volatile int chunks = 3;
    private volatile long chunkGapMillis = 0L;
    private volatile int chunkPayloadSize = 10;
    private volatile boolean stallAfterFirstChunk = false;

    RawSseServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().name("raw-sse-acceptor").start(this::acceptLoop);
    }

    String endpoint() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort() + "/v1/chat/completions";
    }

    RawSseServer chunks(int chunks) {
        this.chunks = chunks;
        return this;
    }

    /** 每个分片之间停这么久再发，用于构造「总时长超过连接超时」的长回答。 */
    RawSseServer chunkGapMillis(long gapMillis) {
        this.chunkGapMillis = gapMillis;
        return this;
    }

    RawSseServer chunkPayloadSize(int payloadSize) {
        this.chunkPayloadSize = payloadSize;
        return this;
    }

    RawSseServer stallAfterFirstChunk() {
        this.stallAfterFirstChunk = true;
        return this;
    }

    int acceptedConnections() {
        return accepted.get();
    }

    int closedConnections() {
        return peerClosed.get();
    }

    boolean awaitPeerClose(long timeoutMillis) throws InterruptedException {
        return firstPeerClose.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 测试收尾，关闭失败无影响
        }
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException closed) {
                return;
            }
            accepted.incrementAndGet();
            Thread.ofVirtual().name("raw-sse-connection").start(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (readRequest(in)) {
                writeResponse(out);
            }
        } catch (IOException peerGone) {
            // 对端提前断开（客户端超时或主动放弃），这正是我们要观察的信号
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        peerClosed.incrementAndGet();
        firstPeerClose.countDown();
    }

    /** 读掉一个请求（首行 + 头 + Content-Length 指定的 body）；连接已被对端关闭时返回 false。 */
    private boolean readRequest(InputStream in) throws IOException {
        String firstLine = readLine(in);
        if (firstLine == null) {
            return false;
        }
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        int remaining = contentLength;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= (int) skipped;
            } else if (in.read() < 0) {
                return false;
            } else {
                remaining--;
            }
        }
        return true;
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) read);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void writeResponse(OutputStream out) throws IOException, InterruptedException {
        byte[] body = sseBody().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        if (stallAfterFirstChunk) {
            int firstChunk = chunkLength();
            out.write(body, 0, firstChunk);
            out.flush();
            Thread.sleep(STALL_MILLIS);
            return;
        }

        if (chunkGapMillis == 0L) {
            out.write(body);
            out.flush();
            return;
        }

        // 逐分片发送：总时长可以远超连接超时，但每片之间的间隔都小于读空闲超时
        int perChunk = chunkLength();
        for (int offset = 0; offset < body.length; offset += perChunk) {
            out.write(body, offset, Math.min(perChunk, body.length - offset));
            out.flush();
            Thread.sleep(chunkGapMillis);
        }
    }

    private int chunkLength() {
        return ("data: " + payloadJson() + "\n\n").getBytes(StandardCharsets.UTF_8).length;
    }

    private String sseBody() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < chunks; i++) {
            body.append("data: ").append(payloadJson()).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return body.toString();
    }

    private String payloadJson() {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + "x".repeat(chunkPayloadSize) + "\"}}]}";
    }
}

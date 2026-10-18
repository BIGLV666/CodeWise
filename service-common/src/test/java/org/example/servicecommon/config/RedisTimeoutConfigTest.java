package org.example.servicecommon.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Redis “快速失败”配置守卫 + 故障注入测试。
 *
 * <p>背景见 {@link RedisConfig} 的可用性约定：Lettuce 命令超时默认 60s，Redis 假死时
 * 每个带凭证的请求要经过「凭证失效 Lua + 限流」两次调用，会把请求拖到分钟级。
 * 各服务本地 yaml 必须显式配置 {@code spring.data.redis.timeout/connect-timeout: 2s}。</p>
 *
 * <p>测试链路是端到端的：读取服务 yaml → 交给 Spring Boot 的
 * {@link RedisAutoConfiguration} 装配真实的 {@link LettuceConnectionFactory} →
 * 指向一个只 accept、不响应任何字节的本地 TCP 服务（假死 Redis），
 * 断言命令在配置的超时（2s）附近失败，而不是等 Lettuce 默认的 60s。</p>
 */
class RedisTimeoutConfigTest {

    /** 使用 Redis 的 7 个服务：新增 Redis 服务时必须同步加入此清单与 yaml 配置。 */
    private static final List<String> REDIS_SERVICES = List.of(
            "service-user",
            "service-question",
            "service-review",
            "service-community",
            "service-message",
            "service-ai",
            "service-judge");

    private static final Duration EXPECTED_TIMEOUT = Duration.ofSeconds(2);

    /** 超时必须以失败场景的上限判定：默认 60s 会远超此值。 */
    private static final long FAST_FAIL_UPPER_BOUND_MS = 5_000;

    /**
     * 并发场景的上限：假死网络下 Lettuce 会在命令超时后重连重试，尾部耗时会大于单条命令的
     * 2s 超时，但仍必须远低于默认超时（20 个连接若各自等 60s 会到 20 分钟级）。
     */
    private static final long CONCURRENT_UPPER_BOUND_MS = 15_000;
    private static final long CONCURRENT_WALL_BOUND_MS = 20_000;

    /** 守卫：7 个用 Redis 的服务本地 yaml 必须都声明 2s 的 timeout / connect-timeout，且不能带 host。 */
    @Test
    void allRedisServicesConfigureFastFail() throws IOException {
        for (String service : REDIS_SERVICES) {
            Map<String, Object> flat = flatConfig(service);
            // host 由 Nacos / 环境变量下发；本地写 host 会改变 RedisConfig 的 @ConditionalOnProperty 行为
            assertFalse(flat.containsKey("spring.data.redis.host"),
                    service + " 不应在本地配置 spring.data.redis.host");

            RedisProperties properties = bind(service, flat);
            assertEquals(EXPECTED_TIMEOUT, properties.getTimeout(),
                    service + " 的 spring.data.redis.timeout 必须是 2s");
            assertEquals(EXPECTED_TIMEOUT, properties.getConnectTimeout(),
                    service + " 的 spring.data.redis.connect-timeout 必须是 2s");
        }
    }

    /** 守卫：没引 Redis 依赖的服务（网关）不应出现 spring.data.redis.timeout，防止误加。 */
    @Test
    void nonRedisServicesHaveNoRedisTimeout() throws IOException {
        Map<String, Object> flat = flatConfig("service-gateway");
        assertFalse(flat.containsKey("spring.data.redis.timeout"),
                "service-gateway 未引入 Redis 依赖，不应配置 spring.data.redis.timeout");
    }

    /** 链路：Boot 自动配置确实把这两个属性传给了真实的 LettuceConnectionFactory。 */
    @Test
    void springBootAppliesTimeoutToLettuceConnectionFactory() throws IOException {
        redisContext("service-user", 6379).run(context -> {
            LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
            assertEquals(EXPECTED_TIMEOUT, factory.getClientConfiguration().getCommandTimeout(),
                    "Boot 未把 spring.data.redis.timeout 传给 Lettuce 命令超时");
            assertEquals(EXPECTED_TIMEOUT,
                    factory.getClientConfiguration().getClientOptions()
                            .orElseThrow().getSocketOptions().getConnectTimeout(),
                    "Boot 未把 spring.data.redis.connect-timeout 传给 Lettuce 连接超时");
        });
    }

    /** 故障注入：假死 Redis 下单条命令应在 2s 超时附近失败，而不是等默认 60s。 */
    @Test
    void singleCommandFailsFastOnBlackholedRedis() throws Exception {
        try (BlackholeRedis blackhole = new BlackholeRedis()) {
            redisContext("service-user", blackhole.port()).run(context -> {
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                long start = System.nanoTime();
                assertThrows(Exception.class, () -> {
                    try (RedisConnection connection = factory.getConnection()) {
                        connection.ping();
                    }
                }, "假死 Redis 上 ping 应超时失败");
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("假死 Redis 单条: %dms%n", elapsedMs);
                assertTrue(elapsedMs < FAST_FAIL_UPPER_BOUND_MS,
                        "单条命令耗时 " + elapsedMs + "ms，未在配置的 " + EXPECTED_TIMEOUT
                                + " 附近快速失败（Lettuce 默认会等 60s）");
            });
        }
    }

    /** 压测：20 并发同时打到假死 Redis，验证全部有界失败、超时没有被串行放大。 */
    @Test
    void twentyConcurrentCommandsFailFastOnBlackholedRedis() throws Exception {
        int concurrency = 20;
        try (BlackholeRedis blackhole = new BlackholeRedis()) {
            redisContext("service-question", blackhole.port()).run(context -> {
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                // 每条命令独占连接：模拟 20 个并发请求同时遇到假死 Redis，避免共享连接把
                // Lettuce 的“串行重建连接”计入耗时（那是驱动行为，不属于本次超时配置）。
                factory.setShareNativeConnection(false);
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                try {
                    CountDownLatch ready = new CountDownLatch(concurrency);
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<Long>> futures = new ArrayList<>();
                    for (int i = 0; i < concurrency; i++) {
                        futures.add(pool.submit(() -> {
                            ready.countDown();
                            start.await();
                            long begin = System.nanoTime();
                            try (RedisConnection connection = factory.getConnection()) {
                                connection.ping();
                                fail("假死 Redis 不应返回成功");
                            } catch (Exception expected) {
                                // 预期：命令超时
                            }
                            return (System.nanoTime() - begin) / 1_000_000;
                        }));
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS), "压测线程未全部就绪");
                    long wallStart = System.nanoTime();
                    start.countDown();

                    List<Long> elapsed = new ArrayList<>();
                    for (Future<Long> future : futures) {
                        elapsed.add(future.get(15, TimeUnit.SECONDS));
                    }
                    elapsed.sort(Long::compareTo);
                    long p50 = elapsed.get(concurrency / 2);
                    long p95 = elapsed.get(Math.min(concurrency - 1, (int) Math.ceil(concurrency * 0.95) - 1));
                    long maxMs = elapsed.get(concurrency - 1);
                    long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
                    System.out.printf("假死 Redis 并发=%d: P50=%dms P95=%dms max=%dms wall=%dms%n",
                            concurrency, p50, p95, maxMs, wallMs);

                    assertTrue(maxMs < CONCURRENT_UPPER_BOUND_MS,
                            concurrency + " 并发下单条最长耗时 " + maxMs + "ms，未快速失败"
                                    + "（假死网络下 Lettuce 会重连重试，但不应接近默认的 60s）");
                    assertTrue(wallMs < CONCURRENT_WALL_BOUND_MS,
                            concurrency + " 并发总耗时 " + wallMs + "ms，超时被串行放大");
                } finally {
                    pool.shutdownNow();
                }
            });
        }
    }

    /**
     * 用真实服务 yaml 里的超时值装配 Boot 的 Redis 自动配置。
     * {@code spring.config.import} 等无关属性不注入，避免测试去连 Nacos。
     */
    private static ApplicationContextRunner redisContext(String service, int port) throws IOException {
        Map<String, Object> flat = flatConfig(service);
        Object timeout = flat.get("spring.data.redis.timeout");
        Object connectTimeout = flat.get("spring.data.redis.connect-timeout");
        assertNotNull(timeout, service + " 缺少 spring.data.redis.timeout");
        assertNotNull(connectTimeout, service + " 缺少 spring.data.redis.connect-timeout");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + port,
                        "spring.data.redis.timeout=" + timeout,
                        "spring.data.redis.connect-timeout=" + connectTimeout);
    }

    /** 用 Spring Boot 的 Binder 按正式绑定规则读取服务 yaml，验证属性名与 Duration 解析。 */
    private static RedisProperties bind(String service, Map<String, Object> flat) {
        Binder binder = new Binder(new MapConfigurationPropertySource(flat));
        RedisProperties properties = binder
                .bind("spring.data.redis", Bindable.of(RedisProperties.class))
                .orElse(null);
        assertNotNull(properties, service + " 缺少 spring.data.redis 配置");
        return properties;
    }

    private static Map<String, Object> flatConfig(String service) throws IOException {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", loadYaml(service), flat);
        return flat;
    }

    private static Map<String, Object> loadYaml(String service) throws IOException {
        Path resources = repoRoot().resolve(service).resolve("src/main/resources");
        Path configFile = Files.exists(resources.resolve("application.yaml"))
                ? resources.resolve("application.yaml")
                : resources.resolve("application.yml");
        assertTrue(Files.exists(configFile), service + " 找不到 " + configFile);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream in = Files.newInputStream(configFile)) {
            return new Yaml(options).load(in);
        }
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else {
                target.put(key, value);
            }
        }
    }

    /** 从测试工作目录向上找到仓库根（同时含 service-common 与 service-user）。 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("service-common"))
                    && Files.isDirectory(dir.resolve("service-user"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 CodeWise 仓库根目录");
    }

    /** 只 accept、不回复任何字节的 TCP 服务：模拟“连接还在但 Redis 不响应”的假死状态。 */
    private static final class BlackholeRedis implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread acceptor;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        private BlackholeRedis() throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.acceptor = new Thread(() -> {
                try {
                    while (!serverSocket.isClosed()) {
                        accepted.add(serverSocket.accept());
                    }
                } catch (IOException ignored) {
                    // close() 打断 accept，测试正常结束
                }
            }, "redis-blackhole");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            for (Socket socket : accepted) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 清理阶段忽略
                }
            }
            serverSocket.close();
        }
    }
}

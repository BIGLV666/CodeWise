package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.service.EmailService;
import org.example.serviceuser.controller.UserController;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对真实 Redis 执行登录风控 Lua 与前置冻结校验，验证阈值、滑动窗口、档位、TTL、
 * 清理语义、元数据缓存和并发原子性。
 *
 * <p>无 Docker 环境时整类自动跳过（{@code disabledWithoutDocker}）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class LoginRiskLuaIntegrationTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "alice";
    private static final String EMAIL = "alice@example.com";
    private static final String[] STEPS = {"30", "60", "300", "600", "1800", "3600", "21600", "43200", "86400"};

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private UserMapper userMapper;
    private EmailService emailService;
    private LoginUserMetaCache metaCache;
    private AccountProtectionAsyncHandlers handler;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }

        userMapper = mock(UserMapper.class);
        emailService = mock(EmailService.class);
        lenient().when(userMapper.selectOne(any())).thenReturn(user());
        lenient().when(userMapper.selectById(USER_ID)).thenReturn(user());
        metaCache = new LoginUserMetaCache(redis, userMapper);
        handler = new AccountProtectionAsyncHandlers(redis, metaCache, userMapper, emailService);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void fourthFailureDoesNotTriggerAndFifthTriggers() {
        fail(4);

        assertFalse(exists(RedisContext.revokeBeforeKey(USER_ID)), "第 4 次不应写失效线");
        assertFalse(exists(RedisContext.loginRiskCountKey(USER_ID)), "第 4 次不应增加风控次数");
        assertEquals(4L, redis.opsForZSet().zCard(RedisContext.loginFailWindowKey(USER_ID)));

        fail(1);

        assertTrue(exists(RedisContext.revokeBeforeKey(USER_ID)), "第 5 次应写失效线");
        assertEquals("1", value(RedisContext.loginRiskCountKey(USER_ID)));
        assertTrue(exists(RedisContext.loginFreezeKey(USER_ID)), "第 5 次应写冻结 key");
        assertFalse(exists(RedisContext.loginFailWindowKey(USER_ID)), "触发后滑动窗口应清空");
        assertBetween(ttl(RedisContext.loginFreezeKey(USER_ID)), 25, 30);
    }

    @Test
    void failureWindowDropsEntriesOlderThanWindow() throws Exception {
        RedisScript<List> script = new DefaultRedisScript<>(
                AccountProtectionAsyncHandlers.LOGIN_FAILURE_SCRIPT_TEXT, List.class);
        List<String> keys = keys();
        long windowMillis = 300L;

        for (int i = 0; i < 4; i++) {
            eval(script, keys, String.valueOf(windowMillis));
        }
        Thread.sleep(500L);
        List<Long> result = eval(script, keys, String.valueOf(windowMillis));

        assertEquals(0L, result.get(0), "窗口外旧失败应被清理，第 5 次不应触发");
        assertEquals(1L, result.get(1));
        assertEquals(1L, redis.opsForZSet().zCard(RedisContext.loginFailWindowKey(USER_ID)));
    }

    @Test
    void freezeEscalatesAndCapsAtOneDay() {
        for (int risk = 1; risk <= 9; risk++) {
            fail(5);
            assertEquals(String.valueOf(risk), value(RedisContext.loginRiskCountKey(USER_ID)));
            long expected = Long.parseLong(STEPS[risk - 1]);
            long ttl = ttl(RedisContext.loginFreezeKey(USER_ID));
            assertBetween(ttl, Math.max(1, expected - 3), expected);
        }

        fail(5);
        assertEquals("10", value(RedisContext.loginRiskCountKey(USER_ID)));
        assertBetween(ttl(RedisContext.loginFreezeKey(USER_ID)), 86400 - 3, 86400);
    }

    @Test
    void revokeBeforeIsPlainEpochSecondsWithSevenDayTtl() {
        fail(5);

        String value = value(RedisContext.revokeBeforeKey(USER_ID));
        assertNotNull(value);
        assertTrue(value.matches("\\d+"), "Lua 的 tonumber 需要裸数字，实际值: " + value);
        long now = Instant.now().getEpochSecond();
        assertTrue(Math.abs(Long.parseLong(value) - now) <= 5, "失效线应是当前 epoch 秒");

        long ttl = ttl(RedisContext.revokeBeforeKey(USER_ID));
        assertBetween(ttl, RedisContext.SESSION_REVOKE_TTL_SECONDS - 5,
                RedisContext.SESSION_REVOKE_TTL_SECONDS);
    }

    @Test
    void successClearsWindowRiskAndFreezeButKeepsRevokeBefore() {
        fail(5);
        String revoke = value(RedisContext.revokeBeforeKey(USER_ID));
        assertNotNull(revoke);

        handler.loginSuccess(USER_ID, USERNAME);

        assertFalse(exists(RedisContext.loginFailWindowKey(USER_ID)));
        assertFalse(exists(RedisContext.loginRiskCountKey(USER_ID)));
        assertFalse(exists(RedisContext.loginFreezeKey(USER_ID)));
        assertTrue(exists(RedisContext.revokeBeforeKey(USER_ID)),
                "成功登录不能清失效线，否则封禁/改密期间未使用的旧 token 会复活");
        assertEquals(revoke, value(RedisContext.revokeBeforeKey(USER_ID)));
    }

    @Test
    void frozenAccountIsRejectedBeforePasswordCheck() throws Exception {
        fail(5);
        LoginFreezePreFilter preFilter = new LoginFreezePreFilter(metaCache, redis);
        FilterContext context = mock(FilterContext.class);
        Method login = UserController.class.getMethod("login", String.class, String.class);
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        // 预热缓存，模拟正常请求已经通过登录写入过元数据
        metaCache.put(USER_ID, 1, USERNAME);

        assertFalse(preFilter.doFilter(context));
        verify(context).setRejectStatus(429);
    }

    @Test
    void repeatedFailuresDoNotQueryDatabaseAfterCacheWarmup() {
        fail(100);
        clearInvocations(userMapper);

        fail(100);

        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void concurrentFailuresDoNotLoseUpdates() throws Exception {
        // 预热元数据缓存，避免并发首波把测试变成数据库压测
        metaCache.put(USER_ID, 1, USERNAME, EMAIL);

        int total = 1000;
        int workers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                Callable<Void> task = () -> {
                    start.await();
                    fail(1);
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 每 5 次失败触发一次并清空窗口，1000 次应恰好触发 200 次。
        assertEquals(String.valueOf(total / 5), value(RedisContext.loginRiskCountKey(USER_ID)));
        assertBetween(ttl(RedisContext.loginFreezeKey(USER_ID)), 86400 - 3, 86400);
        assertFalse(exists(RedisContext.loginFailWindowKey(USER_ID)));
        verify(userMapper, never()).selectOne(any());
        verify(userMapper, times(1)).selectById(USER_ID);
        verify(emailService, times(1)).sendEmail(
                org.mockito.ArgumentMatchers.eq(EMAIL),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            handler.loginFailure(USERNAME);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Long> eval(RedisScript<List> script, List<String> keys, String windowMillis) {
        return (List<Long>) redis.execute(script, keys,
                java.util.UUID.randomUUID().toString(),
                windowMillis,
                "5",
                "86400",
                String.join(",", STEPS),
                String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS));
    }

    private List<String> keys() {
        return List.of(
                RedisContext.loginFailWindowKey(USER_ID),
                RedisContext.loginRiskCountKey(USER_ID),
                RedisContext.revokeBeforeKey(USER_ID),
                RedisContext.loginFreezeKey(USER_ID)
        );
    }

    private boolean exists(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    private String value(String key) {
        return redis.opsForValue().get(key);
    }

    private long ttl(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? -2L : ttl;
    }

    private static void assertBetween(long actual, long min, long max) {
        assertTrue(actual >= min && actual <= max,
                "TTL 应在 [" + min + ", " + max + "] 内，实际 " + actual);
    }

    private static User user() {
        return User.builder()
                .userId(USER_ID)
                .userName(USERNAME)
                .email(EMAIL)
                .status(1)
                .build();
    }
}

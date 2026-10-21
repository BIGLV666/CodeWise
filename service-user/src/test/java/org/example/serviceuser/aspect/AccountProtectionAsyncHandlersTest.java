package org.example.serviceuser.aspect;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.service.EmailService;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录风控核心方法（同步入参）的离线单测：不发 Redis，只验证参数组装、缓存取数、邮件和 fail-open。
 */
@ExtendWith(MockitoExtension.class)
class AccountProtectionAsyncHandlersTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "alice";
    private static final String EMAIL = "alice@example.com";

    @Mock private LoginUserMetaCache metaCache;
    @Mock private UserMapper userMapper;
    @Mock private EmailService emailService;

    private RecordingStringRedisTemplate redis;
    private AccountProtectionAsyncHandlers handler;

    @BeforeEach
    void setUp() {
        redis = new RecordingStringRedisTemplate();
        handler = new AccountProtectionAsyncHandlers(redis, metaCache, userMapper, emailService);
    }

    // ---------- 失败计数 ----------

    @Test
    void blankAccountIsIgnored() {
        handler.loginFailure("   ");

        assertNull(redis.keys);
        verify(metaCache, never()).get(anyString());
    }

    @Test
    void unknownAccountIsIgnored() {
        when(metaCache.get(USERNAME)).thenReturn(LoginUserMeta.NOT_FOUND);

        handler.loginFailure(USERNAME);

        assertNull(redis.keys);
    }

    @Test
    void bannedUserIsIgnored() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 0));

        handler.loginFailure(USERNAME);

        assertNull(redis.keys);
    }

    @Test
    void cancelledUserIsIgnored() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 2));

        handler.loginFailure(USERNAME);

        assertNull(redis.keys);
    }

    @Test
    void failureScriptGetsWindowPolicyAndPlainRedisKeys() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(1L, 5L, 30L, 1L);
        when(redis.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userMapper.selectById(USER_ID)).thenReturn(user());

        handler.loginFailure(USERNAME);

        assertEquals(List.of(
                RedisContext.loginFailWindowKey(USER_ID),
                RedisContext.loginRiskCountKey(USER_ID),
                RedisContext.revokeBeforeKey(USER_ID),
                RedisContext.loginFreezeKey(USER_ID)
        ), redis.keys);
        assertEquals(6, redis.args.length);
        assertTrue(String.valueOf(redis.args[0]).length() > 0);
        assertEquals("180000", redis.args[1]);
        assertEquals("5", redis.args[2]);
        assertEquals("86400", redis.args[3]);
        assertEquals("30,60,300,600,1800,3600,21600,43200,86400", redis.args[4]);
        assertEquals(String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS), redis.args[5]);
    }

    @Test
    void belowThresholdDoesNotSendMail() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(0L, 4L, 0L, 0L);

        handler.loginFailure(USERNAME);

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void triggerSendsHtmlMailWithoutCodeKeyword() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(1L, 5L, 30L, 1L);
        when(redis.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userMapper.selectById(USER_ID)).thenReturn(user());

        handler.loginFailure(USERNAME);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq(EMAIL), eq("CodeWise 登录安全提醒"), content.capture());
        String html = content.getValue();
        assertTrue(html.startsWith("<html"), "service-message 靠 <html 前缀识别 HTML 邮件");
        assertTrue(html.contains("冻结至"));
        assertTrue(html.contains("30 秒"));
        assertFalse(html.contains("验证码"), "不能在正文出现「验证码」，否则会被验证码模板误判");
    }

    @Test
    void mailIsThrottledToOnePerHour() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(1L, 5L, 30L, 1L);
        when(redis.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        handler.loginFailure(USERNAME);

        verify(userMapper, never()).selectById(any());
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    void mailFailureIsSwallowedAndThrottleReleased() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(1L, 5L, 30L, 1L);
        when(redis.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userMapper.selectById(USER_ID)).thenReturn(user());
        org.mockito.Mockito.doThrow(new IllegalStateException("mq down"))
                .when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> handler.loginFailure(USERNAME));

        assertTrue(redis.deletedKeys.contains(RedisContext.loginRiskMailKey(USER_ID)),
                "发送失败必须释放节流 key，下一次触发才能重试");
    }

    @Test
    void mailWithoutEmailReleasesThrottle() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.result = List.of(1L, 5L, 30L, 1L);
        when(redis.valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        User noEmail = user();
        noEmail.setEmail(" ");
        when(userMapper.selectById(USER_ID)).thenReturn(noEmail);

        handler.loginFailure(USERNAME);

        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        assertTrue(redis.deletedKeys.contains(RedisContext.loginRiskMailKey(USER_ID)));
    }

    @Test
    void redisFailureDoesNotEscape() {
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        redis.failure = new RedisConnectionFailureException("redis down");

        assertDoesNotThrow(() -> handler.loginFailure(USERNAME));
    }

    @Test
    void cacheFailureDoesNotEscape() {
        when(metaCache.get(USERNAME)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> handler.loginFailure(USERNAME));
        assertNull(redis.keys);
    }

    // ---------- 成功清理 ----------

    @Test
    void successClearsWindowRiskAndFreezeOnly() {
        redis.result = 1L;

        handler.loginSuccess(USER_ID, USERNAME);

        assertEquals(List.of(
                RedisContext.loginFailWindowKey(USER_ID),
                RedisContext.loginRiskCountKey(USER_ID),
                RedisContext.loginFreezeKey(USER_ID)
        ), redis.keys);
        assertTrue(redis.args == null || redis.args.length == 0);
    }

    @Test
    void successWithoutUserIdIsIgnored() {
        handler.loginSuccess(null, USERNAME);

        assertNull(redis.keys);
    }

    @Test
    void successRedisFailureDoesNotEscape() {
        redis.failure = new RedisConnectionFailureException("redis down");

        assertDoesNotThrow(() -> handler.loginSuccess(USER_ID, USERNAME));
    }

    private static User user() {
        return User.builder()
                .userId(USER_ID)
                .userName(USERNAME)
                .email(EMAIL)
                .status(1)
                .build();
    }

    /** 记录脚本、KEYS、ARGV 和删除的 key；不建立真实 Redis 连接。 */
    private static final class RecordingStringRedisTemplate extends StringRedisTemplate {
        private final ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(ValueOperations.class);
        private final List<String> deletedKeys = new ArrayList<>();
        private RedisScript<?> script;
        private List<String> keys;
        private Object[] args;
        private Object result;
        private RuntimeException failure;

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOps;
        }

        @Override
        public Boolean delete(String key) {
            deletedKeys.add(key);
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            this.script = script;
            this.keys = keys;
            this.args = args;
            if (failure != null) {
                throw failure;
            }
            return (T) result;
        }
    }
}

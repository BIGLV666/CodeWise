package org.example.serviceuser.aspect;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import io.github.biglv666.apigovernance.async.event.AsyncError;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.service.EmailService;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.example.serviceuser.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录风控异步 handler 的离线单测：不发 Redis，只验证入参组装、错误分流和 fail-open。
 */
@ExtendWith(MockitoExtension.class)
class AccountProtectionAsyncHandlersTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "alice";

    @Mock private UserMapper userMapper;
    @Mock private EmailService emailService;

    private RecordingStringRedisTemplate redis;
    private AccountProtectionAsyncHandlers handler;

    @BeforeEach
    void setUp() {
        redis = new RecordingStringRedisTemplate();
        handler = new AccountProtectionAsyncHandlers(redis, userMapper, emailService);
    }

    @Test
    void nonLoginFailureIsIgnored() {
        handler.loginFailure(event(AsyncPhase.AFTER_ERROR,
                new AsyncError(BizException.class.getName(), "验证码不正确"), Map.of("username", USERNAME)));

        assertNull(redis.keys);
        verify(userMapper, times(0)).selectOne(any());
    }

    @Test
    void missingUsernameIsIgnored() {
        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of()));

        assertNull(redis.keys);
        verify(userMapper, times(0)).selectOne(any());
    }

    @Test
    void missingUserIsIgnored() {
        when(userMapper.selectOne(any())).thenReturn(null);

        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME)));

        assertNull(redis.keys);
        verify(userMapper, times(2)).selectOne(any());
    }

    @Test
    void bannedUserIsIgnored() {
        User banned = user();
        banned.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(banned);

        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME)));

        assertNull(redis.keys);
    }

    @Test
    void cancelledUserIsIgnored() {
        User cancelled = user();
        cancelled.setStatus(2);
        when(userMapper.selectOne(any())).thenReturn(cancelled);

        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME)));

        assertNull(redis.keys);
    }

    @Test
    void usernameIsQueriedBeforeEmail() {
        User emailOwner = user();
        when(userMapper.selectOne(any())).thenReturn(null, emailOwner);
        redis.result = List.of(0L, 4L, 0L, 0L);

        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", "alice@example.com")));

        ArgumentCaptor<Wrapper<User>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(userMapper, times(2)).selectOne(queries.capture());
        assertQuery(queries.getAllValues().get(0), "user_name", "alice@example.com");
        assertQuery(queries.getAllValues().get(1), "email", "alice@example.com");
    }

    @Test
    void failureScriptGetsWindowPolicyAndPlainRedisKeys() {
        when(userMapper.selectOne(any())).thenReturn(user());
        redis.result = List.of(1L, 5L, 30L, 1L);

        handler.loginFailure(event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME)));

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
    void belowThresholdDoesNotThrow() {
        when(userMapper.selectOne(any())).thenReturn(user());
        redis.result = List.of(0L, 4L, 0L, 0L);

        assertDoesNotThrow(() -> handler.loginFailure(
                event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME))));
    }

    @Test
    void redisFailureDoesNotEscapeAsyncHandler() {
        when(userMapper.selectOne(any())).thenReturn(user());
        redis.failure = new RedisConnectionFailureException("redis down");

        assertDoesNotThrow(() -> handler.loginFailure(
                event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME))));
    }

    @Test
    void databaseFailureDoesNotEscapeAsyncHandler() {
        when(userMapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> handler.loginFailure(
                event(AsyncPhase.AFTER_ERROR, loginFailureError(), Map.of("username", USERNAME))));
        assertNull(redis.keys);
    }

    @Test
    void successClearsWindowRiskAndFreezeOnly() {
        redis.result = 1L;

        handler.LoginSuccess(event(AsyncPhase.AFTER_SUCCESS, null,
                Map.of("userId", USER_ID, "username", USERNAME)));

        assertEquals(List.of(
                RedisContext.loginFailWindowKey(USER_ID),
                RedisContext.loginRiskCountKey(USER_ID),
                RedisContext.loginFreezeKey(USER_ID)
        ), redis.keys);
        assertTrue(redis.args == null || redis.args.length == 0);
    }

    @Test
    void successWithoutUserIdIsIgnored() {
        handler.LoginSuccess(event(AsyncPhase.AFTER_SUCCESS, null, Map.of("username", USERNAME)));

        assertNull(redis.keys);
    }

    @Test
    void successRedisFailureDoesNotEscapeAsyncHandler() {
        redis.failure = new RedisConnectionFailureException("redis down");

        assertDoesNotThrow(() -> handler.LoginSuccess(event(AsyncPhase.AFTER_SUCCESS, null,
                Map.of("userId", USER_ID, "username", USERNAME))));
    }

    private static AsyncError loginFailureError() {
        return new AsyncError(BizException.class.getName(), UserErrorCode.LOGIN_FAILED.getMessage());
    }

    private static AsyncEvent event(AsyncPhase phase, AsyncError error, Map<String, Object> data) {
        Instant now = Instant.now();
        return new AsyncEvent("event-1", "user-login", phase,
                AccountProtectionAsyncHandlersTest.class.getName(), "test",
                now, now, 0L, error, data);
    }

    private static User user() {
        return User.builder()
                .userId(USER_ID)
                .userName(USERNAME)
                .email("alice@example.com")
                .status(1)
                .build();
    }

    private static void assertQuery(Wrapper<User> query, String column, String value) {
        assertTrue(query.getSqlSegment().contains(column + " ="), query.getSqlSegment());
        assertEquals(Map.of("MPGENVAL1", value),
                ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>) query)
                        .getParamNameValuePairs());
    }

    /** 记录脚本、KEYS、ARGV；不建立真实 Redis 连接。 */
    private static final class RecordingStringRedisTemplate extends StringRedisTemplate {
        private RedisScript<?> script;
        private List<String> keys;
        private Object[] args;
        private Object result;
        private RuntimeException failure;

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

package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.serviceuser.controller.UserController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录前置冻结校验单测：冻结/过期/未知账号/异常降级。
 */
@ExtendWith(MockitoExtension.class)
class LoginFreezePreFilterTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "alice";

    @Mock private LoginUserMetaCache metaCache;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private FilterContext context;

    private LoginFreezePreFilter filter;
    private Method login;

    @BeforeEach
    void setUp() throws Exception {
        filter = new LoginFreezePreFilter(metaCache, redis);
        login = UserController.class.getMethod("login", String.class, String.class);
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void frozenAccountIsRejectedWithRemainingTime() throws Exception {
        long remaining = 125;
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        when(valueOps.get(RedisContext.loginFreezeKey(USER_ID)))
                .thenReturn(String.valueOf(Instant.now().getEpochSecond() + remaining));

        boolean pass = filter.doFilter(context);

        assertFalse(pass);
        verify(context).setRejectStatus(429);
        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(context).setRejectReason(reason.capture());
        assertTrue(reason.getValue().contains("冻结"), reason.getValue());
        assertTrue(reason.getValue().contains("3 分钟"), reason.getValue());
    }

    @Test
    void expiredFreezeIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        when(valueOps.get(RedisContext.loginFreezeKey(USER_ID)))
                .thenReturn(String.valueOf(Instant.now().getEpochSecond() - 1));

        assertTrue(filter.doFilter(context));
        verify(context, never()).setRejectStatus(429);
    }

    @Test
    void missingFreezeKeyIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        when(valueOps.get(RedisContext.loginFreezeKey(USER_ID))).thenReturn(null);

        assertTrue(filter.doFilter(context));
    }

    @Test
    void unknownAccountIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(LoginUserMeta.NOT_FOUND);

        assertTrue(filter.doFilter(context));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void blankAccountIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{"  ", "pwd"});

        assertTrue(filter.doFilter(context));
        verify(metaCache, never()).get(anyString());
    }

    @Test
    void nonLoginMethodIsAllowed() throws Exception {
        Method logout = UserController.class.getMethod("logout");
        when(context.getMethod()).thenReturn(logout);

        assertTrue(filter.doFilter(context));
        verify(metaCache, never()).get(anyString());
    }

    @Test
    void nullContextIsAllowed() {
        assertTrue(filter.doFilter(null));
    }

    @Test
    void cacheFailureIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenThrow(new IllegalStateException("db down"));

        assertTrue(filter.doFilter(context));
    }

    @Test
    void redisFailureIsAllowed() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 1));
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        assertTrue(filter.doFilter(context));
    }

    @Test
    void bannedAccountIsNotReportedAsFrozen() throws Exception {
        when(context.getMethod()).thenReturn(login);
        when(context.getArgs()).thenReturn(new Object[]{USERNAME, "pwd"});
        when(metaCache.get(USERNAME)).thenReturn(new LoginUserMeta(USER_ID, 0));

        assertTrue(filter.doFilter(context));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void emailLoginIsCovered() throws Exception {
        Method emailLogin = UserController.class.getMethod("emailLogin", String.class, String.class);
        when(context.getMethod()).thenReturn(emailLogin);
        when(context.getArgs()).thenReturn(new Object[]{"alice@example.com", "pwd"});
        when(metaCache.get("alice@example.com")).thenReturn(new LoginUserMeta(USER_ID, 1));
        when(valueOps.get(RedisContext.loginFreezeKey(USER_ID)))
                .thenReturn(String.valueOf(Instant.now().getEpochSecond() + 30));

        assertFalse(filter.doFilter(context));
    }

    @Test
    void remainingTimeFormatting() {
        assertEquals("1 秒", LoginFreezePreFilter.formatRemaining(0));
        assertEquals("30 秒", LoginFreezePreFilter.formatRemaining(30));
        assertEquals("1 分钟", LoginFreezePreFilter.formatRemaining(60));
        assertEquals("2 分钟", LoginFreezePreFilter.formatRemaining(61));
        assertEquals("1 小时", LoginFreezePreFilter.formatRemaining(3600));
        assertEquals("2 小时", LoginFreezePreFilter.formatRemaining(3601));
        assertEquals("1 天", LoginFreezePreFilter.formatRemaining(86400));
        assertEquals("2 天", LoginFreezePreFilter.formatRemaining(86401));
    }
}

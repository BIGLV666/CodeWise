package org.example.serviceuser.aspect;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

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
 * 账号元数据缓存的读穿透、正/负缓存、TTL 与 Redis 降级测试。
 */
@ExtendWith(MockitoExtension.class)
class LoginUserMetaCacheTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "alice";
    private static final String EMAIL = "alice@example.com";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private UserMapper userMapper;

    private LoginUserMetaCache cache;

    @BeforeEach
    void setUp() {
        cache = new LoginUserMetaCache(redis, userMapper);
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void positiveCacheHitDoesNotQueryDatabase() {
        when(valueOps.get(RedisContext.loginUserMetaKey(USERNAME))).thenReturn("42|1");

        LoginUserMeta meta = cache.get(USERNAME);

        assertEquals(USER_ID, meta.userId());
        assertEquals(1, meta.status());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void negativeCacheHitDoesNotQueryDatabase() {
        when(valueOps.get(RedisContext.loginUserMetaKey(USERNAME))).thenReturn("0");

        LoginUserMeta meta = cache.get(USERNAME);

        assertFalse(meta.exists());
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void cacheMissQueriesDatabaseAndCachesPositiveResult() {
        when(valueOps.get(RedisContext.loginUserMetaKey(USERNAME))).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(user());

        LoginUserMeta meta = cache.get(USERNAME);

        assertEquals(USER_ID, meta.userId());
        verify(valueOps).set(RedisContext.loginUserMetaKey(USERNAME), "42|1", Duration.ofMinutes(10));
    }

    @Test
    void unknownAccountWritesShortNegativeCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(null);

        LoginUserMeta meta = cache.get(USERNAME);

        assertFalse(meta.exists());
        verify(valueOps).set(RedisContext.loginUserMetaKey(USERNAME), "0", Duration.ofSeconds(60));
    }

    @Test
    void redisReadFailureFallsBackToDatabase() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(userMapper.selectOne(any())).thenReturn(user());

        LoginUserMeta meta = cache.get(USERNAME);

        assertEquals(USER_ID, meta.userId());
        verify(userMapper, org.mockito.Mockito.times(1)).selectOne(any());
    }

    @Test
    void malformedCacheValueFallsBackToDatabase() {
        when(valueOps.get(anyString())).thenReturn("not-a-meta-value");
        when(userMapper.selectOne(any())).thenReturn(user());

        LoginUserMeta meta = cache.get(USERNAME);

        assertEquals(USER_ID, meta.userId());
        verify(valueOps).set(RedisContext.loginUserMetaKey(USERNAME), "42|1", Duration.ofMinutes(10));
    }

    @Test
    void cacheWriteFailureDoesNotBreakLookup() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(userMapper.selectOne(any())).thenReturn(user());
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        LoginUserMeta meta = cache.get(USERNAME);

        assertEquals(USER_ID, meta.userId());
    }

    @Test
    void blankAccountIsTreatedAsNotFound() {
        LoginUserMeta meta = cache.get("  ");

        assertFalse(meta.exists());
        verify(redis, never()).opsForValue();
    }

    @Test
    void putWritesAllLoginAccounts() {
        cache.put(USER_ID, 1, USERNAME, EMAIL, " ");

        verify(valueOps).set(RedisContext.loginUserMetaKey(USERNAME), "42|1", Duration.ofMinutes(10));
        verify(valueOps).set(RedisContext.loginUserMetaKey(EMAIL), "42|1", Duration.ofMinutes(10));
        verify(valueOps, never()).set(RedisContext.loginUserMetaKey(" "), "42|1", Duration.ofMinutes(10));
    }

    @Test
    void nullStatusIsSerializedAsUnknown() {
        cache.put(USER_ID, null, USERNAME);

        verify(valueOps).set(eq(RedisContext.loginUserMetaKey(USERNAME)), eq("42|-1"), any(Duration.class));
    }

    @Test
    void nullUserIdIsIgnoredOnPut() {
        cache.put(null, 1, USERNAME);

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void notFoundRecordHasNullFields() {
        assertNull(LoginUserMeta.NOT_FOUND.userId());
        assertNull(LoginUserMeta.NOT_FOUND.status());
        assertTrue(LoginUserMeta.NOT_FOUND.exists() == false);
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

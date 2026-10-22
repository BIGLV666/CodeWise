package org.example.serviceuser.service;

import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.JwtUntil;
import org.example.serviceuser.until.PasswordEncoding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 冻结 / 注销账号时的凭证作废逻辑（纯 Mockito，不起 Spring 上下文）。
 *
 * <p>要守住的是一条安全不变式：<b>账号一旦被停用，该用户所有旧 token 必须立刻不能用</b>。
 * JWT 是无状态的，网关只验签名不看账号状态，真正让旧 token 失效的是
 * {@code UserAuthInterceptor} 对 {@code revoke_before:<userId>} 失效线的检查
 * （拿网关注入的 JWT {@code iat} 与之比较，{@code iat <= 失效线} 即拒绝）。</p>
 *
 * <p>所以「冻结/注销有没有把失效线写进去」是必须测的行为，而不是实现细节。每个用例都同时
 * 断言两件事：失效线写入的参数（key / epoch 秒原文 / 7 天 TTL）与写入方式是
 * {@link StringRedisTemplate}（值必须是裸数字，Jackson 值序列化器会加引号导致 Lua 的
 * {@code tonumber} 拿到 nil），以及<b>写入失败时状态变更必须没有发生</b>——否则就会留下
 * 「账号已停用但旧 token 仍可用」的窗口，这正是本次要消灭的问题。</p>
 *
 * <p>JWT 用 {@code @Spy} + 测试密钥走真实 HS256 签名，与 {@code UserServiceLogoutTest}
 * 同一套路；密钥仅用于单测。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceAccountRevokeTest {
    private static final String JWT_SECRET = "unit-test-jwt-secret-key-must-be-at-least-256-bits!!";
    private static final long TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long USER_ID = 1L;
    private static final String USER_NAME = "alice";
    private static final String RAW_PASSWORD = "correct-password";
    private static final String REVOKE_KEY = RedisContext.revokeBeforeKey(USER_ID);

    @Spy private JwtUntil jwtUntil = new JwtUntil();
    @Mock private UserMailService userMailService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> stringValueOperations;
    @Mock private UserMapper userMapper;
    @InjectMocks private UserService service;

    private String token;
    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtUntil, "secret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUntil, "expiration", TOKEN_TTL_MS);
        token = jwtUntil.generateToken(USER_ID, USER_NAME);
        UserContext.setUserId(USER_ID);
        UserContext.setUserToken(token);

        user = new User();
        user.setUserId(USER_ID);
        user.setUserName(USER_NAME);
        user.setPassword(PasswordEncoding.encode(RAW_PASSWORD));
        user.setStatus(1);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ---------- 冻结账号 ----------

    @Test
    void freezeWritesUserLevelRevokeLineBeforeChangingStatus() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        long before = Instant.now().getEpochSecond();

        service.freezeUser("违规测试", RAW_PASSWORD);

        long after = Instant.now().getEpochSecond();
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(stringValueOperations).set(eq(REVOKE_KEY), value.capture(),
                eq(RedisContext.SESSION_REVOKE_TTL_SECONDS), eq(TimeUnit.SECONDS));
        long written = Long.parseLong(value.getValue());
        assertTrue(written >= before && written <= after, "失效线值必须是当前 epoch 秒");
        assertEquals(0, user.getStatus(), "冻结应把 status 置为 0");
        assertEquals("违规测试", user.getBanReason());

        // 顺序：先作废凭证，再落库状态变更
        InOrder order = inOrder(stringValueOperations, userMapper);
        order.verify(stringValueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        order.verify(userMapper).updateById(any(User.class));
    }

    @Test
    void freezeWithWrongPasswordNeitherRevokesNorChangesStatus() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        BizException error = assertThrows(BizException.class,
                () -> service.freezeUser("违规测试", "wrong-password"));

        assertSame(UserErrorCode.PASSWORD_INCORRECT, error.getErrorCode());
        // 密码都没验过就作废凭证，等于把攻击者的一次试探变成一次强制下线
        verifyNoInteractions(redisTemplate, stringRedisTemplate, userMailService, rabbitTemplate);
        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(1, user.getStatus(), "校验失败不应改变账号状态");
    }

    @Test
    void freezeAbortsWithoutChangingStatusWhenRevokeFails() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connect refused");
        doThrow(failure).when(stringValueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        BizException error = assertThrows(BizException.class,
                () -> service.freezeUser("违规测试", RAW_PASSWORD));

        assertSame(UserErrorCode.SESSION_REVOKE_FAILED, error.getErrorCode());
        assertSame(failure, error.getCause());
        // 关键：不能出现「账号已冻结但旧 token 还能用」
        verify(userMapper, never()).updateById(any(User.class));
        verifyNoInteractions(userMailService);
    }

    @Test
    void freezeRevokesAuthenticatedUserNotTokenSubject() {
        // 上下文里塞一个属于别人的 token：作废目标必须以网关注入的 userId 为准，
        // 不能被 token 里的 subject 带偏（否则会产生「A 能触发作废 B」的越权面）
        UserContext.setUserToken(jwtUntil.generateToken(USER_ID + 1, "someone-else"));
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        service.freezeUser("违规测试", RAW_PASSWORD);

        verify(stringValueOperations).set(eq(REVOKE_KEY), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(stringValueOperations, never())
                .set(eq(RedisContext.revokeBeforeKey(USER_ID + 1)), anyString(), anyLong(), any(TimeUnit.class));
        assertEquals(0, user.getStatus());
    }

    // ---------- 注销账号 ----------

    @Test
    void deleteWritesUserLevelRevokeLineBeforeCancellingAccount() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        long before = Instant.now().getEpochSecond();

        service.deleteUser();

        long after = Instant.now().getEpochSecond();
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(stringValueOperations).set(eq(REVOKE_KEY), value.capture(),
                eq(RedisContext.SESSION_REVOKE_TTL_SECONDS), eq(TimeUnit.SECONDS));
        long written = Long.parseLong(value.getValue());
        assertTrue(written >= before && written <= after, "失效线值必须是当前 epoch 秒");
        assertEquals(2, user.getStatus(), "注销应把 status 置为 2");

        InOrder order = inOrder(stringValueOperations, userMapper);
        order.verify(stringValueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        order.verify(userMapper).updateById(any(User.class));
    }

    @Test
    void deleteAbortsWithoutCancellingWhenRevokeFails() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connect refused");
        doThrow(failure).when(stringValueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        BizException error = assertThrows(BizException.class, () -> service.deleteUser());

        assertSame(UserErrorCode.SESSION_REVOKE_FAILED, error.getErrorCode());
        assertSame(failure, error.getCause());
        verify(userMapper, never()).updateById(any(User.class));
        assertEquals(1, user.getStatus(), "作废失败时账号不应变成已注销");
    }

    @Test
    void deleteOfAlreadyCancelledAccountDoesNotRevoke() {
        user.setStatus(2);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        BizException error = assertThrows(BizException.class, () -> service.deleteUser());

        assertSame(UserErrorCode.USER_CANCELLED, error.getErrorCode());
        verifyNoInteractions(redisTemplate, stringRedisTemplate);
        verify(userMapper, never()).updateById(any(User.class));
    }
}

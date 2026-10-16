package org.example.serviceuser.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.exception.UserErrorCode;
import org.example.serviceuser.until.JwtUntil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code UserService.logout()} 的离线单测（纯 Mockito，不起 Spring 上下文）。
 *
 * <p>JWT 刻意不用 {@code @Mock JwtUntil}：mock 只能 stub 出解析结果，而登出逻辑的核心正是
 * “把网关注入的 token 再解析一次、比对 subject、用原 token 写 redis 黑名单”，
 * 因此这里 {@code @Spy} + {@link ReflectionTestUtils} 注入测试密钥，走真实 HS256 签名，
 * 才能覆盖过期 / 篡改 / 身份不符 / subject 非数字等真实边角。</p>
 *
 * <p>测试密钥只用于本单测，长度满足 HS256 的 256 位要求，与任何部署环境的真实密钥无关。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceLogoutTest {
    private static final String JWT_SECRET = "unit-test-jwt-secret-key-must-be-at-least-256-bits!!";
    private static final String ATTACKER_SECRET = "attacker-jwt-secret-key-must-be-at-least-256-bits!!";
    private static final long TOKEN_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long USER_ID = 1L;
    private static final String USER_NAME = "admin";
    /** 黑名单 TTL：必须不小于 token 有效期，否则 token 没过期黑名单就失效了。 */
    private static final long BLACKLIST_TTL_DAYS = 7L;

    /** 真实 JwtUntil 的 spy：等价于“mock 一个能签发/解析真 token 的 JWT 桩”。 */
    @Spy private JwtUntil jwtUntil = new JwtUntil();
    @Mock private UserMailService userMailService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @InjectMocks private UserService service;

    /** 网关注入的原始 token（`Bearer ` 前缀已由网关去掉）。 */
    private String token;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtUntil, "secret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUntil, "expiration", TOKEN_TTL_MS);
        token = jwtUntil.generateToken(USER_ID, USER_NAME);
        UserContext.setUserId(USER_ID);
        UserContext.setUserToken(token);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void logoutBlacklistsTheExactGatewayTokenForSevenDays() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.logout();

        verify(valueOperations).set(RedisContext.LOGOUT_TOKEN + ":" + token, token,
                BLACKLIST_TTL_DAYS, TimeUnit.DAYS);
        verifyNoMoreInteractions(valueOperations);
        verifyNoInteractions(userMailService, rabbitTemplate);
    }

    @Test
    void logoutRejectsTokenIssuedForAnotherUser() {
        UserContext.setUserToken(jwtUntil.generateToken(USER_ID + 1, "someone-else"));

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate, valueOperations, userMailService, rabbitTemplate);
    }

    @Test
    void logoutRejectsMissingToken() {
        UserContext.setUserToken(null);

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void logoutRejectsExpiredToken() {
        ReflectionTestUtils.setField(jwtUntil, "expiration", -1000L);
        UserContext.setUserToken(jwtUntil.generateToken(USER_ID, USER_NAME));

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logoutRejectsTokenSignedWithAnotherSecret() {
        JwtUntil attacker = new JwtUntil();
        ReflectionTestUtils.setField(attacker, "secret", ATTACKER_SECRET);
        ReflectionTestUtils.setField(attacker, "expiration", TOKEN_TTL_MS);
        UserContext.setUserToken(attacker.generateToken(USER_ID, USER_NAME));

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logoutRejectsTokenWhoseSubjectIsNotAUserId() {
        UserContext.setUserToken(Jwts.builder()
                .subject("not-a-user-id")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TOKEN_TTL_MS))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact());

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logoutRejectsRequestWithoutAuthenticatedUser() {
        UserContext.setUserId(null);

        assertTokenInvalid(() -> service.logout());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void logoutReportsBusinessErrorWhenRedisWriteFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("connect refused");
        doThrow(failure).when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        BizException error = assertThrows(BizException.class, () -> service.logout());

        assertSame(UserErrorCode.LOGOUT_FAILED, error.getErrorCode());
        assertEquals(UserErrorCode.LOGOUT_FAILED.getCode(), error.getCode());
        assertEquals(UserErrorCode.LOGOUT_FAILED.getMessage(), error.getMessage());
        assertSame(failure, error.getCause());
    }

    @Test
    void logoutReportsBusinessErrorWhenRedisTemplateHasNoValueOperations() {
        when(redisTemplate.opsForValue()).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.logout());

        assertSame(UserErrorCode.LOGOUT_FAILED, error.getErrorCode());
    }

    private static BizException assertTokenInvalid(org.junit.jupiter.api.function.Executable action) {
        BizException error = assertThrows(BizException.class, action);
        assertSame(CommonErrorCode.TOKEN_INVALID, error.getErrorCode());
        assertEquals(CommonErrorCode.TOKEN_INVALID.getCode(), error.getCode());
        assertEquals(CommonErrorCode.TOKEN_INVALID.getMessage(), error.getMessage());
        return error;
    }
}

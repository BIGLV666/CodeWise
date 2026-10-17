package org.example.serviceuser.service;

import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 封禁 / 解封 / 改密的「用户级凭证失效线」行为（纯 Mockito，不起 Spring 上下文）。
 *
 * <p>方案：封禁与改密都写 {@code revoke_before:<userId> = 当前 epoch 秒}，
 * 拦截器用网关注入的 JWT {@code iat} 与之比较（{@code iat <= 失效线} 即拒）。
 * 因此要守住的不变式是：</p>
 * <ol>
 *   <li><b>封禁/改密必须写下失效线</b>，否则该用户所有旧凭证（含多端）都拦不住；</li>
 *   <li><b>值必须是裸数字的 epoch 秒</b>：Lua 要 {@code tonumber} 解析，
 *       带引号的 JSON 字符串会解析成 nil，判定直接失效；</li>
 *   <li><b>解封不做任何 Redis 操作</b>：失效线要保留（清掉会让封禁期间没用过的旧 token 复活），
 *       新登录的 token iat 更晚自然通过；</li>
 *   <li><b>写失效线失败必须让整个操作失败</b>（事务回滚），不能出现「库里封了、凭证却还活着」；
 *       通知邮件只能 best-effort，不该回滚封禁。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceBanTest {
    private static final long USER_ID = 7L;
    private static final int BAN_DAYS = 30;

    @Mock private JwtUntil jwtUntil;
    @Mock private UserMailService userMailService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> stringValueOperations;
    @Mock private ValueOperations<String, Object> objectValueOperations;
    @Mock private UserMapper userMapper;
    @InjectMocks private UserService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUserId(USER_ID);
        user.setUserName("bob");
        user.setStatus(1);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ---------- 封禁 ----------

    @Test
    void banWritesRevokeLineWithTokenLifetimeTtl() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        long before = Instant.now().getEpochSecond();
        service.banUser(USER_ID, BAN_DAYS);
        long after = Instant.now().getEpochSecond();

        assertRevokeLineWritten(before, after);
        assertEquals(0, user.getStatus(), "封禁应把 status 置为 0");
        verify(userMailService).sendBanMail(user);
    }

    /** 封禁通知邮件走 MQ，属通知而非封禁本身：邮件通道故障不得回滚封禁。 */
    @Test
    void banSurvivesMailChannelFailure() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        doThrow(new IllegalStateException("mq down")).when(userMailService).sendBanMail(any(User.class));

        service.banUser(USER_ID, BAN_DAYS);

        assertEquals(0, user.getStatus(), "邮件失败不影响封禁结果");
        verify(stringValueOperations).set(eq(RedisContext.revokeBeforeKey(USER_ID)), anyString(),
                eq(RedisContext.SESSION_REVOKE_TTL_SECONDS), eq(TimeUnit.SECONDS));
    }

    /** 写失效线失败要抛出并交给事务回滚：不能留下「库里封了但旧凭证还能用」。 */
    @Test
    void banFailsWhenRevokeLineWriteFails() {
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(stringValueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        BizException e = assertThrows(BizException.class, () -> service.banUser(USER_ID, BAN_DAYS));

        assertEquals(UserErrorCode.SESSION_REVOKE_FAILED.getCode(), e.getCode());
        verifyNoInteractions(userMailService);
    }

    @Test
    void banRejectsInvalidBanTime() {
        for (Integer invalid : new Integer[]{null, 0, -1, 3651}) {
            BizException e = assertThrows(BizException.class, () -> service.banUser(USER_ID, invalid));
            assertEquals(CommonErrorCode.PARAM_INVALID.getCode(), e.getCode(), "banTime=" + invalid);
        }
        verifyNoInteractions(userMapper, redisTemplate, stringRedisTemplate, userMailService);
    }

    @Test
    void banRejectsCancelledAccountAndToleratesNullStatus() {
        user.setStatus(2);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        BizException e = assertThrows(BizException.class, () -> service.banUser(USER_ID, BAN_DAYS));
        assertEquals(UserErrorCode.USER_CANCELLED.getCode(), e.getCode());

        user.setStatus(null);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        service.banUser(USER_ID, BAN_DAYS);
        assertEquals(0, user.getStatus(), "status 为 null 时不能 NPE，应正常封禁");
    }

    @Test
    void banRejectsMissingUser() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.banUser(USER_ID, BAN_DAYS));

        assertEquals(UserErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
    }

    // ---------- 解封 ----------

    /** 解封只改库、不动 Redis：失效线保留，旧 token 不复活，新 token 靠 iat 自然通过。 */
    @Test
    void unbanTouchesNoRedisStateAndKeepsRevokeLine() {
        user.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        service.unBanUser(USER_ID);

        assertEquals(1, user.getStatus());
        assertNull(user.getBanTime());
        verifyNoInteractions(redisTemplate, stringRedisTemplate);
        verify(userMailService).sendUnbanMail(user);
    }

    @Test
    void unbanSurvivesMailChannelFailure() {
        user.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        doThrow(new IllegalStateException("mq down")).when(userMailService).sendUnbanMail(any(User.class));

        service.unBanUser(USER_ID);

        assertEquals(1, user.getStatus());
    }

    @Test
    void unbanRejectsAccountNotBanned() {
        for (Integer status : new Integer[]{1, 2, null}) {
            user.setStatus(status);
            when(userMapper.selectById(USER_ID)).thenReturn(user);
            BizException e = assertThrows(BizException.class, () -> service.unBanUser(USER_ID));
            assertEquals(UserErrorCode.USER_NOT_FROZEN.getCode(), e.getCode(), "status=" + status);
        }
        verifyNoInteractions(redisTemplate, stringRedisTemplate);
    }

    // ---------- 失效线写入口 ----------

    /** 公开入口（找回密码）没有登录身份，传空 userId 必须直接失败，不能写出 revoke_before:null 死键。 */
    @Test
    void revokeIssuedTokensRejectsNullUserId() {
        BizException e = assertThrows(BizException.class,
                () -> service.revokeIssuedTokens(null, UserErrorCode.SESSION_REVOKE_FAILED));

        assertEquals(UserErrorCode.SESSION_REVOKE_FAILED.getCode(), e.getCode());
        verifyNoInteractions(stringRedisTemplate);
    }

    // ---------- 改密链路：失效的必须是「被改密的那个人」 ----------

    /**
     * P0 回归：{@code /api/user/updatefromcode} 是公开路径，UserContext 里没有登录身份，
     * 必须用改密记录自己的 userId 写失效线——写 UserContext 会得到 {@code revoke_before:null}，
     * 既拦不住人又留下死键。
     */
    @Test
    void passwordResetRevokesTargetUserEvenWithoutLoginIdentity() {
        String email = "bob@example.com";
        String code = "123456";
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", code);
        payload.put("userId", String.valueOf(USER_ID));
        when(redisTemplate.opsForValue()).thenReturn(objectValueOperations);
        when(objectValueOperations.get("user_code:reset--" + email)).thenReturn(payload);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        assertNull(UserContext.getUserId(), "公开入口不应带登录身份（测试前提）");

        long before = Instant.now().getEpochSecond();
        service.updatePasswordForCode(email, code, "new-password");
        long after = Instant.now().getEpochSecond();

        assertRevokeLineWritten(before, after);
        verify(stringValueOperations, never())
                .set(eq("revoke_before:null"), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 登录态改密：失效当前登录用户本人的全部凭证（与找回密码链路区分开）。 */
    @Test
    void passwordChangeRevokesAllTokensOfCurrentUser() {
        user.setPassword(PasswordEncoding.encode("old-password"));
        UserContext.setUserId(USER_ID);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);

        long before = Instant.now().getEpochSecond();
        service.updatePassword("old-password", "new-password");
        long after = Instant.now().getEpochSecond();

        assertRevokeLineWritten(before, after);
    }

    // ---------- helpers ----------

    private void assertRevokeLineWritten(long before, long after) {
        org.mockito.ArgumentCaptor<String> valueCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(stringValueOperations).set(eq(RedisContext.revokeBeforeKey(USER_ID)), valueCaptor.capture(),
                eq(RedisContext.SESSION_REVOKE_TTL_SECONDS), eq(TimeUnit.SECONDS));

        String written = valueCaptor.getValue();
        long seconds;
        try {
            seconds = Long.parseLong(written);
        } catch (NumberFormatException e) {
            throw new AssertionError("失效线必须是裸数字 epoch 秒（Lua 要 tonumber），实际: " + written, e);
        }
        assertTrue(seconds >= before && seconds <= after,
                "失效线应取当前时间: written=" + seconds + " 期望区间[" + before + "," + after + "]");
    }
}

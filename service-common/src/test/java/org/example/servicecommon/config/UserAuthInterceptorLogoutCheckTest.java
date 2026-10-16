package org.example.servicecommon.config;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下游登出黑名单校验（离线单测，不起 Spring 上下文）。
 *
 * <p>覆盖：网关注入的 {@code X-User-Token} 是否落入 {@link UserContext}（logout 依赖它）、
 * 已注销 token 的拦截文案与响应形态、无 token 时不做无谓 redis 查询、
 * Redis 故障时保持“失败放行”不阻断业务请求、登录等白名单路径不受黑名单影响。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAuthInterceptorLogoutCheckTest {
    private static final String INTERNAL_TOKEN = "unit-test-internal-token";
    private static final String TOKEN = "header.payload.signature";

    @Mock private RedisTemplate<String, Object> redisTemplate;

    private UserAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new UserAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "internalToken", INTERNAL_TOKEN);
        ReflectionTestUtils.setField(interceptor, "redisTemplate", redisTemplate);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void blacklistedTokenIsRejectedWithBusinessCodeInBody() throws Exception {
        when(redisTemplate.hasKey(RedisContext.LOGOUT_TOKEN + ":" + TOKEN)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(authenticatedRequest(TOKEN), response, new Object()));

        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":403"), body);
        assertTrue(body.contains("请重新登录"), body);
    }

    /** 正常 token：放行，且业务代码能通过 UserContext 读到同一串 token（logout 写黑名单用它）。 */
    @Test
    void liveTokenIsForwardedAndReadableByBusinessCode() throws Exception {
        when(redisTemplate.hasKey(RedisContext.LOGOUT_TOKEN + ":" + TOKEN)).thenReturn(false);

        assertTrue(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));

        assertEquals(TOKEN, UserContext.getToken());
        assertEquals(42L, UserContext.getUserId());
        assertEquals("lv", UserContext.getUserName());
    }

    /** 无 token 头（如带内部 Token 的服务间调用）：跳过 redis，避免 "logout_token:null" 空转。 */
    @Test
    void missingTokenSkipsRedisLookup() throws Exception {
        assertTrue(interceptor.preHandle(authenticatedRequest(null), new MockHttpServletResponse(), new Object()));

        verifyNoInteractions(redisTemplate);
    }

    /** Redis 故障按“失败放行”处理：只落日志，不把整站请求变成 500。 */
    @Test
    void redisOutageDoesNotBlockBusinessRequests() throws Exception {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        assertTrue(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));
    }

    /** 登录等白名单路径在黑名单校验之前放行，登录不会被登出逻辑连带拦截。 */
    @Test
    void publicPathIsUnaffectedByBlacklist() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Token", TOKEN);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        verifyNoInteractions(redisTemplate);
    }

    /** 模拟网关转发：内部 Token + 身份三件套，token 为 null 时即“网关注入但未带 token”的形态。 */
    private static MockHttpServletRequest authenticatedRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/logout");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Id", "42");
        request.addHeader("X-User-Name", "lv");
        request.addHeader("X-Real-IP", "203.0.113.7");
        if (token != null) {
            request.addHeader("X-User-Token", token);
        }
        return request;
    }
}

package org.example.servicecommon.config;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下游凭证失效校验（离线单测，不起 Spring 上下文）。
 *
 * <p>覆盖两条失效通道：token 桶（登出，按 token 原文）与用户级失效线
 * （封禁／改密，按网关注入的 JWT {@code iat} 比较），以及网关注入的
 * {@code X-User-Token} 是否落入 {@link UserContext}（业务侧 logout 依赖它）、
 * 无 token 时不做无谓 redis 查询、缺 userId / userId 非法时退化为只查 token 桶、
 * iat 缺失时下传空串、Redis 故障「失败放行」、登录等白名单路径不受影响。</p>
 *
 * <p>判定顺序与边界条件在 Lua 里，因此另有两个脚本正文断言（不删失效线、不做
 * 额外的写放大），改脚本前先看它们的原因注释。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAuthInterceptorLogoutCheckTest {
    private static final String INTERNAL_TOKEN = "unit-test-internal-token";
    private static final String TOKEN = "header.payload.signature";
    private static final String IAT = "1792222199";
    private static final long PASS = 1L;
    private static final long REJECT = 0L;

    @Mock private StringRedisTemplate stringRedisTemplate;

    private UserAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new UserAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "internalToken", INTERNAL_TOKEN);
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 脚本判定为「失效」：HTTP 保持 200，响应体带业务码与统一文案。 */
    @Test
    void revokedSessionIsRejectedWithBusinessCodeInBody() throws Exception {
        whenRevokeCheck().thenReturn(REJECT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(authenticatedRequest(TOKEN), response, new Object()));

        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":403"), body);
        assertTrue(body.contains("请重新登录"), body);
    }

    /** 脚本入参接齐三件：token 桶 + 用户级失效线两个 key，以及 token / TTL / iat 三个参数。 */
    @Test
    void scriptReceivesTokenBucketRevokeLineAndIssuedAt() throws Exception {
        whenRevokeCheck().thenReturn(REJECT);

        assertFalse(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(),
                eq(TOKEN), eq(String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS)), eq(IAT));
        assertEquals(List.of(RedisContext.logoutTokenKey(TOKEN), RedisContext.revokeBeforeKey(42L)),
                keysCaptor.getValue());
    }

    /** 失效线只读不删：删掉会让封禁期间没用过的旧 token 在解封后复活。 */
    @Test
    void scriptOnlyReadsRevokeLineAndNeverDeletesIt() {
        String script = UserAuthInterceptor.SESSION_REVOKE_SCRIPT_TEXT;

        assertTrue(script.contains("EXISTS"), script);
        assertTrue(script.contains("GET"), script);
        assertTrue(script.contains("tonumber(ARGV[3])"), "应按 iat 与失效线比较: " + script);
        assertFalse(script.contains("DEL"), "脚本不得删除失效线: " + script);
    }

    /** 正常 token：放行，且业务代码能通过 UserContext 读到同一串 token（logout 写黑名单用它）。 */
    @Test
    void liveTokenIsForwardedAndReadableByBusinessCode() throws Exception {
        whenRevokeCheck().thenReturn(PASS);

        assertTrue(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));

        assertEquals(TOKEN, UserContext.getToken());
        assertEquals(42L, UserContext.getUserId());
        assertEquals("lv", UserContext.getUserName());
    }

    /** 无 token 头（如带内部 Token 的服务间调用）：跳过 redis，避免 "logout_token:null" 空转。 */
    @Test
    void missingTokenSkipsRedisLookup() throws Exception {
        assertTrue(interceptor.preHandle(authenticatedRequest(null), new MockHttpServletResponse(), new Object()));

        verifyNoInteractions(stringRedisTemplate);
    }

    /** 有 token 但无 userId：只查 token 桶，脚本按 KEYS 数量自适应。 */
    @Test
    void missingUserIdChecksTokenBucketOnly() throws Exception {
        whenRevokeCheck().thenReturn(PASS);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/logout");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Token", TOKEN);
        request.addHeader("X-User-Token-Iat", IAT);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), any(), any(), any());
        assertEquals(List.of(RedisContext.logoutTokenKey(TOKEN)), keysCaptor.getValue());
    }

    /** X-User-Id 非法（如被注入脏值）：退化为只查 token 桶，不影响请求处理。 */
    @Test
    void illegalUserIdFallsBackToTokenBucketOnly() throws Exception {
        whenRevokeCheck().thenReturn(PASS);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/logout");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Id", "not-a-number");
        request.addHeader("X-User-Token", TOKEN);
        request.addHeader("X-User-Token-Iat", IAT);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), any(), any(), any());
        assertEquals(List.of(RedisContext.logoutTokenKey(TOKEN)), keysCaptor.getValue());
    }

    /** 网关未下发 iat（老版本/异常）时传空串，交给 Lua 按「无法证明凭证比失效线新」处理。 */
    @Test
    void missingIssuedAtHeaderIsPassedAsEmptyString() throws Exception {
        whenRevokeCheck().thenReturn(PASS);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/logout");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Id", "42");
        request.addHeader("X-User-Token", TOKEN);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(),
                eq(TOKEN), eq(String.valueOf(RedisContext.SESSION_REVOKE_TTL_SECONDS)), eq(""));
    }

    /** Redis 故障按「失败放行」处理：只落日志，不把整站请求变成 500。 */
    @Test
    void redisOutageDoesNotBlockBusinessRequests() throws Exception {
        whenRevokeCheck().thenThrow(new RedisConnectionFailureException("redis down"));

        assertTrue(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));
    }

    /** 没有 Redis Bean（未引入 redis 的宿主）：跳过校验并放行，不抛 NPE。 */
    @Test
    void missingRedisTemplateFailsOpen() throws Exception {
        ReflectionTestUtils.setField(interceptor, "stringRedisTemplate", null);

        assertTrue(interceptor.preHandle(authenticatedRequest(TOKEN), new MockHttpServletResponse(), new Object()));
    }

    /** 登录等白名单路径在失效校验之前放行，登录不会被登出逻辑连带拦截。 */
    @Test
    void publicPathIsUnaffectedByBlacklist() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Token", TOKEN);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        verifyNoInteractions(stringRedisTemplate);
    }

    /** 脚本调用的统一桩：两 key + 三参数（token / TTL / iat）。 */
    @SuppressWarnings("unchecked")
    private org.mockito.stubbing.OngoingStubbing<Object> whenRevokeCheck() {
        return (org.mockito.stubbing.OngoingStubbing<Object>) when(
                stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()));
    }

    /** 模拟网关转发：内部 Token + 身份四件套（含 iat），token 为 null 时即「网关注入但未带 token」的形态。 */
    private static MockHttpServletRequest authenticatedRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/logout");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Id", "42");
        request.addHeader("X-User-Name", "lv");
        request.addHeader("X-Real-IP", "203.0.113.7");
        if (token != null) {
            request.addHeader("X-User-Token", token);
            request.addHeader("X-User-Token-Iat", IAT);
        }
        return request;
    }
}

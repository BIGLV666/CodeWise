package org.example.servicemessage.websocket.websocketConfig;

import org.example.servicecommon.until.JwtUntil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 握手内部令牌校验的离线单测（不起 Spring 上下文）。
 *
 * <p>覆盖：X-Internal-Token 正确/错误/缺失三种情形，以及网关注入
 * X-User-Id 与 URL token 两条认证路径。</p>
 */
class WebSocketAuthInterceptorTest {

    /** 仅用于单测签名的密钥（长度满足 HS256 的 256 位要求），非真实密钥。 */
    private static final String JWT_SECRET = "unit-test-jwt-secret-key-must-be-at-least-256-bits!!";

    private static final String INTERNAL_TOKEN = "unit-test-internal-token";

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(INTERNAL_TOKEN, JWT_SECRET, 86400000L);
    }

    private boolean handshake(MockHttpServletRequest servletRequest) throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        return interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);
    }

    private Map<String, Object> handshakeWithAttributes(MockHttpServletRequest servletRequest) throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                null,
                attributes);
        assertThat(ok).isTrue();
        return attributes;
    }

    /** 正确内部令牌 + 网关注入的 X-User-Id → 握手通过，attributes 带 userId。 */
    @Test
    void correctInternalTokenWithUserIdHeaderPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/websocket");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.addHeader("X-User-Id", "42");

        Map<String, Object> attributes = handshakeWithAttributes(request);

        assertThat(attributes.get("userId")).isEqualTo(42L);
    }

    /** 错误内部令牌 → 拒绝握手（即使带合法 X-User-Id 也不放行）。 */
    @Test
    void wrongInternalTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/websocket");
        request.addHeader("X-Internal-Token", "forged-token");
        request.addHeader("X-User-Id", "42");

        assertThat(handshake(request)).isFalse();
    }

    /** 缺失内部令牌 → 拒绝握手。 */
    @Test
    void missingInternalTokenRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/websocket");
        request.addHeader("X-User-Id", "42");

        assertThat(handshake(request)).isFalse();
    }

    /** 内部令牌正确但无 X-User-Id、无 token → 拒绝握手。 */
    @Test
    void correctInternalTokenWithoutIdentityRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/websocket");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);

        assertThat(handshake(request)).isFalse();
    }

    /** 内部令牌正确 + URL token → 握手通过，userId 取自 token。 */
    @Test
    void correctInternalTokenWithUrlTokenPasses() throws Exception {
        JwtUntil jwtUntil = new JwtUntil(JWT_SECRET, 86400000L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/websocket");
        request.addHeader("X-Internal-Token", INTERNAL_TOKEN);
        request.setParameter("token", jwtUntil.generateToken(42L, "alice"));

        Map<String, Object> attributes = handshakeWithAttributes(request);

        assertThat(attributes.get("userId")).isEqualTo(42L);
    }
}

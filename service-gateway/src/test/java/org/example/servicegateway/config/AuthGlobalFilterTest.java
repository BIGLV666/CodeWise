package org.example.servicegateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.example.servicegateway.until.JwtUntil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthGlobalFilter 头清洗与注入的离线单测（纯 Mockito，不起 Spring 上下文）。
 *
 * <p>覆盖：公共路径/鉴权路径/WebSocket 分支的"先剥离后注入"纪律、
 * 伪造 X-Internal-Token 拒绝、trust-forwarded-for 两种部署形态下的
 * X-Forwarded-For 处置与客户端 IP 解析。</p>
 */
class AuthGlobalFilterTest {

    /** 仅用于单测签名的密钥（长度满足 HS256 的 256 位要求），非真实密钥。 */
    private static final String JWT_SECRET = "unit-test-jwt-secret-key-must-be-at-least-256-bits!!";

    private static final String INTERNAL_TOKEN = "unit-test-internal-token";

    private static final InetSocketAddress REMOTE = new InetSocketAddress("203.0.113.7", 51000);

    private JwtUntil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUntil();
        ReflectionTestUtils.setField(jwtUtil, "secret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
    }

    private GatewayFilterChain mockChain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        return chain;
    }

    private ServerHttpRequest forwarded(GatewayFilterChain chain) {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue().getRequest();
    }

    /**
     * 验收点 1：公共路径携带伪造身份头 → 转发请求不含 X-User-Id/X-User-Name，
     * X-Internal-Token/X-Real-IP 为网关注入值。
     */
    @Test
    void publicPathStripsForgedIdentityHeaders() {
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/login")
                        .remoteAddress(REMOTE)
                        .header("X-User-Id", "999")
                        .header("X-User-Name", "attacker")
                        .header("X-Internal-Token", "forged-token")
                        .header("X-Real-IP", "1.2.3.4")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isNull();
        assertThat(forwarded.getHeaders().getFirst("X-User-Name")).isNull();
        assertThat(forwarded.getHeaders().getFirst("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
        assertThat(forwarded.getHeaders().getFirst("X-Real-IP")).isEqualTo("203.0.113.7");
    }

    /**
     * 验收点 2：合法 Bearer 访问受保护路径 → X-User-Id/X-User-Name 为 token 解析值，
     * 客户端伪造值不透传。
     */
    @Test
    void validBearerInjectsTokenIdentityOverForgedHeaders() {
        String token = jwtUtil.generateToken(42L, "alice");
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/profile")
                        .remoteAddress(REMOTE)
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "1")
                        .header("X-User-Name", "attacker")
                        .header("X-Internal-Token", "forged-token")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        assertThat(forwarded.getHeaders().getFirst("X-User-Name")).isEqualTo("alice");
        assertThat(forwarded.getHeaders().getFirst("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
    }

    /**
     * 验收点 3：伪造 X-Internal-Token 且无 Authorization → 401，不转发。
     */
    @Test
    void forgedInternalTokenWithoutAuthorizationRejected() {
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/profile")
                        .remoteAddress(REMOTE)
                        .header("X-Internal-Token", "forged-token")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any(ServerWebExchange.class));
    }

    /**
     * 验收点 4a：trust-forwarded-for=false（默认直连部署）→ 客户端 XFF 被剥离，
     * X-Real-IP 取 TCP remoteAddress。
     */
    @Test
    void distrustForwardedForStripsXffAndUsesRemoteAddress() {
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/login")
                        .remoteAddress(REMOTE)
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .header("X-Real-IP", "9.9.9.9")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-Forwarded-For")).isNull();
        assertThat(forwarded.getHeaders().getFirst("X-Real-IP")).isEqualTo("203.0.113.7");
    }

    /**
     * 验收点 4b：trust-forwarded-for=true（可信 LB 之后）→ XFF 保留透传，
     * X-Real-IP 取 XFF 首值。
     */
    @Test
    void trustForwardedForKeepsXffAndUsesFirstValue() {
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/login")
                        .remoteAddress(REMOTE)
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-Forwarded-For")).isEqualTo("1.2.3.4, 5.6.7.8");
        assertThat(forwarded.getHeaders().getFirst("X-Real-IP")).isEqualTo("1.2.3.4");
    }

    /**
     * 验收点 5：WebSocket 握手经 URL token 校验 → 注入 X-User-Id/X-User-Name，
     * 伪造 X-User-Name 被替换。
     */
    @Test
    void websocketUrlTokenInjectsUserName() {
        String token = jwtUtil.generateToken(42L, "alice");
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/websocket?token=" + token)
                        .remoteAddress(REMOTE)
                        .header("Upgrade", "websocket")
                        .header("X-User-Id", "1")
                        .header("X-User-Name", "attacker")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        assertThat(forwarded.getHeaders().getFirst("X-User-Name")).isEqualTo("alice");
        assertThat(forwarded.getHeaders().getFirst("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
    }

    /**
     * 验收点 5 补充：token 缺 userName claim → X-User-Name 置空字符串而非透传伪造值。
     */
    @Test
    void websocketTokenWithoutUserNameClaimInjectsEmptyName() {
        String token = Jwts.builder()
                .subject("7")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        AuthGlobalFilter filter = new AuthGlobalFilter(jwtUtil, INTERNAL_TOKEN, false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/websocket?token=" + token)
                        .remoteAddress(REMOTE)
                        .header("Upgrade", "websocket")
                        .header("X-User-Name", "attacker")
                        .build());
        GatewayFilterChain chain = mockChain();

        filter.filter(exchange, chain).block();

        ServerHttpRequest forwarded = forwarded(chain);
        assertThat(forwarded.getHeaders().getFirst("X-User-Id")).isEqualTo("7");
        assertThat(forwarded.getHeaders().getFirst("X-User-Name")).isEmpty();
    }
}

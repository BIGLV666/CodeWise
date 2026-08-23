package org.example.servicegateway.config;

import io.jsonwebtoken.Claims;
import org.example.servicegateway.until.JwtUntil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

/**
 * 网关全局鉴权过滤器：先剥离、后注入。
 *
 * <p>转发前无条件剥离客户端可伪造的四个头（X-User-Id、X-User-Name、
 * X-Internal-Token、X-Real-IP），再由本过滤器按鉴权结果注入唯一可信值；
 * 网关未注入的头一律不会透传下游，公共路径与 WebSocket 分支同理。
 * X-Forwarded-For 仅在 {@code codewise.gateway.trust-forwarded-for=false}
 * （默认，直连部署）时一并剥离；四个头的处置见 {@link #filter} 与 {@link #getClientIp}。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtUntil jwtUtil;

    /** 服务间内部调用令牌，经 X-Internal-Token 下发供下游 UserAuthInterceptor 校验。 */
    private final String internalToken;

    /** 是否信任反向代理写入的 X-Forwarded-For 头，默认 false（直连部署）。 */
    private final boolean trustForwardedFor;

    /**
     * 免鉴权路径白名单。
     * 用精确路径而非 contains 子串匹配：后者会让任何含 "login"/"register" 的路径意外放行。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/user/login",
            "/api/user/emaillogin",
            "/api/user/emailloginforcode",
            "/api/user/getemailcode",
            "/api/user/emailregister",
            "/api/user/register",
            "/api/user/updatepasswordforemail",
            "/api/user/updatefromcode"
    );

    /** 静态资源前缀，无需登录即可访问。 */
    private static final String UPLOADS_PREFIX = "/uploads/";

    public AuthGlobalFilter(JwtUntil jwtUtil,
                            @Value("${codewise.internal-token}") String internalToken,
                            @Value("${codewise.gateway.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.jwtUtil = jwtUtil;
        this.internalToken = internalToken;
        this.trustForwardedFor = trustForwardedFor;
    }

    private boolean isPublicPath(String path) {
        if (path == null) {
            return false;
        }
        // 去掉结尾多余的斜杠，避免 /api/user/login/ 绕过白名单命中鉴权分支
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        return PUBLIC_PATHS.contains(normalized) || normalized.startsWith(UPLOADS_PREFIX);
    }

    /**
     * 解析客户端真实 IP。
     *
     * <p>信任来源取决于部署形态：</p>
     * <ul>
     *   <li>{@code trust-forwarded-for=false}（默认，网关直连客户端）：X-Forwarded-For
     *       与 X-Real-IP 均为客户端可伪造头，只信任 TCP 层 remoteAddress，
     *       且转发前剥离客户端携带的 X-Forwarded-For；</li>
     *   <li>{@code true}（网关部署在可信 LB/反向代理之后）：维持 X-Forwarded-For
     *       首值优先、其次 X-Real-IP、最后 remoteAddress 的现有逻辑，并保留 XFF 头透传。</li>
     * </ul>
     */
    private String getClientIp(ServerHttpRequest request) {
        if (trustForwardedFor) {
            HttpHeaders headers = request.getHeaders();
            // 1. 先取可信代理写入的 X-Forwarded-For
            String ip = headers.getFirst("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // 如果有多个代理，取第一个
                return ip.split(",")[0].trim();
            }
            // 2. 再取 X-Real-IP
            ip = headers.getFirst("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip;
            }
        }
        // 3. 最后取 RemoteAddr
        return Objects.requireNonNull(request.getRemoteAddress()).getHostString();
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 基于剥离前的原始请求头解析客户端 IP（builder 的剥离可能复用底层 header
        //    存储，故必须在剥离前取值，保证 trust 模式下 XFF/X-Real-IP 判定不受影响）
        String ip = getClientIp(request);

        // 2. 先剥离：无条件移除客户端可伪造的身份/来源头（mutate 的 header() 是整值替换，
        //    先移除保证未经网关注入的头不透传；XFF 在不信任代理时同样剥离）
        ServerHttpRequest.Builder requestBuilder = request.mutate();
        requestBuilder.headers(httpHeaders -> {
            httpHeaders.remove("X-User-Id");
            httpHeaders.remove("X-User-Name");
            httpHeaders.remove("X-Internal-Token");
            httpHeaders.remove("X-Real-IP");
            if (!trustForwardedFor) {
                httpHeaders.remove("X-Forwarded-For");
            }
        });

        // 3. 注入网关侧唯一可信值
        requestBuilder
                .header("X-Real-IP", ip)
                .header("X-Internal-Token", internalToken);

        System.out.println("🔍 请求路径: " + path);
        System.out.println("📌 客户端 IP: " + ip);

        // 4. 放行登录/注册/找回密码（身份头已在步骤 2 剥离，此处不带 X-User-Id/X-User-Name 透传）
        if (isPublicPath(path)) {
            System.out.println("✅ 放行: " + path);
            ServerHttpRequest newRequest = requestBuilder.build();
            System.out.println("📌 newRequest 的 X-Real-IP: " + newRequest.getHeaders().getFirst("X-Real-IP"));
            return chain.filter(exchange.mutate().request(newRequest).build());
        }
        // ====== WebSocket 握手特殊处理 ======
        // WebSocket 握手是 HTTP GET 请求，带有 Upgrade: websocket 头
        String upgrade = request.getHeaders().getFirst("Upgrade");
        boolean isWebSocket = "websocket".equalsIgnoreCase(upgrade);

        if (isWebSocket || path.contains("/websocket")) {
            System.out.println("🔌 WebSocket 握手请求: " + path);

            // 从 URL 参数获取 token（SockJS 降级方案）
            String token = request.getQueryParams().getFirst("token");

            // 如果没有，从 Header 获取
            if (token == null || token.isEmpty()) {
                token = request.getHeaders().getFirst("Authorization");
                if (token != null && token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }
            }

            // 验证 Token
            if (token == null || token.isEmpty() || !jwtUtil.validateToken(token)) {
                System.out.println("❌ WebSocket 认证失败: token无效");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.parseLong(claims.getSubject());
            // userName claim 缺失时置空字符串，与鉴权分支口径一致（不让伪造头透传）
            Object userNameClaim = claims.get("userName");
            String userName = userNameClaim == null ? "" : userNameClaim.toString();
            System.out.println("✅ WebSocket 认证成功: userId=" + userId);

            // 注入 userId/userName 到请求头，透传给下游
            ServerHttpRequest newRequest = requestBuilder
                    .header("X-User-Id", String.valueOf(userId))
                    .header("X-User-Name", userName)
                    .header("X-Internal-Token", internalToken)
                    .build();

            ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();
            return chain.filter(newExchange);
        }

        // 5. 其他接口需要 Token
        String token = request.getHeaders().getFirst("Authorization");
        if (token == null || token.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (!jwtUtil.validateToken(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String userName = jwtUtil.getUserNameFromToken(token);


        // 6. 在同一个 requestBuilder 上继续添加请求头
        ServerHttpRequest newRequest = requestBuilder
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Name", String.valueOf(userName))
                .header("X-Internal-Token", internalToken)
                .build();

        ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();
        return chain.filter(newExchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}

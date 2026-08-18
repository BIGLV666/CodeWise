package org.example.servicegateway.config;

import org.example.servicegateway.until.JwtUntil;
import org.springframework.beans.factory.annotation.Autowired;
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

@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUntil jwtUtil;

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

    private String getClientIp(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        // 1. 先取 X-Forwarded-For
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
        // 3. 最后取 RemoteAddr
        return Objects.requireNonNull(request.getRemoteAddress()).getHostString();
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. 获取 IP 并构建请求建造器
        String ip = getClientIp(request);
        ServerHttpRequest.Builder requestBuilder = request.mutate()
                .header("X-Real-IP", ip)
                .header("X-Internal-Token", "codewise-secret-2026");

        System.out.println("🔍 请求路径: " + path);
        System.out.println("📌 客户端 IP: " + ip);

        // 2. 放行登录/注册/找回密码
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

            Long userId = jwtUtil.getUserIdFromToken(token);
            System.out.println("✅ WebSocket 认证成功: userId=" + userId);

            // 添加 userId 到请求头，透传给下游
            ServerHttpRequest newRequest = requestBuilder
                    .header("X-User-Id", String.valueOf(userId))
                    .header("X-Internal-Token", "codewise-secret-2026")
                    .build();

            ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();
            return chain.filter(newExchange);
        }

        // 3. 其他接口需要 Token
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


        // 4. 在同一个 requestBuilder 上继续添加请求头
        ServerHttpRequest newRequest = requestBuilder
                .header("X-User-Id", String.valueOf(userId))
                .header("X-User-Name", String.valueOf(userName))
                .header("X-Internal-Token", "codewise-secret-2026")
                .build();

        ServerWebExchange newExchange = exchange.mutate().request(newRequest).build();
        return chain.filter(newExchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
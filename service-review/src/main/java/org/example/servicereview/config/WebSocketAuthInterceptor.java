package org.example.servicereview.config;

import jakarta.servlet.http.HttpServletRequest;
import org.example.servicecommon.until.JwtUntil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /**
     * 网关注入的内部通信 Token（codewise.internal-token，环境变量
     * CODEWISE_INTERNAL_TOKEN），与 HTTP 侧 UserAuthInterceptor 的校验口径一致：
     * 未携带或不匹配一律拒绝握手，防止绕过网关直接伪造 X-User-Id。
     */
    private final String internalToken;

    private final JwtUntil jwtUtil;

    public WebSocketAuthInterceptor(
            @Value("${codewise.internal-token}") String internalToken,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration-ms}") long jwtExpirationMillis) {
        this.internalToken = internalToken;
        this.jwtUtil = new JwtUntil(jwtSecret, jwtExpirationMillis);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        System.out.println("🔌 WebSocket 握手请求: " + request.getURI());

        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            HttpServletRequest httpRequest = servletRequest.getServletRequest();

            // 网关内部 Token 校验：未携带或不匹配直接 401 拒绝，不信任任何身份头
            String internalTokenHeader = httpRequest.getHeader("X-Internal-Token");
            if (internalTokenHeader == null || !internalToken.equals(internalTokenHeader)) {
                System.out.println("❌ WebSocket 内部 Token 校验失败");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            // 从网关透传的 Header 获取 userId
            String userIdHeader = httpRequest.getHeader("X-User-Id");
            if (userIdHeader != null && !userIdHeader.isEmpty()) {
                try {
                    Long userId = Long.parseLong(userIdHeader);
                    System.out.println("✅ WebSocket 认证成功: userId=" + userId);

                    // ✅ 关键：必须把 userId 放入 attributes
                    attributes.put("userId", userId);
                    System.out.println("📌 attributes userId 已设置: " + attributes.get("userId"));

                    return true;
                } catch (NumberFormatException e) {
                    System.out.println("❌ X-User-Id 格式错误");
                }
            }

            // 从 URL 参数获取 Token
            String token = httpRequest.getParameter("token");
            if (token == null || token.isEmpty()) {
                token = httpRequest.getHeader("Authorization");
                if (token != null && token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }
            }

            if (token != null && !token.isEmpty()) {
                Long userId = jwtUtil.getUserIdFromToken(token);
                if (userId != null) {
                    System.out.println("✅ WebSocket 认证成功: userId=" + userId);

                    // ✅ 关键：必须把 userId 放入 attributes
                    attributes.put("userId", userId);
                    System.out.println("📌 attributes userId 已设置: " + attributes.get("userId"));

                    return true;
                }
            }

            System.out.println("❌ WebSocket 认证失败");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        return false;
    }


    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}

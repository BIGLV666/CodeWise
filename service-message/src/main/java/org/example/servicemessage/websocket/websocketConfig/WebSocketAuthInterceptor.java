package org.example.servicemessage.websocket.websocketConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.servicecommon.until.JwtUntil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器。
 *
 * <p>内部令牌与 JWT 密钥均由配置注入（{@code codewise.internal-token}、
 * {@code jwt.secret}、{@code jwt.expiration-ms}，经环境变量
 * CODEWISE_INTERNAL_TOKEN / JWT_SECRET 提供），不再硬编码。</p>
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtUntil jwtUtil;

    private final String internalToken;

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

            String internalToken =httpRequest.getHeader("X-Internal-Token");

            if(!this.internalToken.equals(internalToken)){
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
            return false;
        }

        return false;
    }


    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}

package org.example.servicecommon.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

/**
 * 下游服务统一身份拦截器：校验网关注入的 {@code X-Internal-Token}，
 * 并把 {@code X-User-Id}、{@code X-User-Name}、{@code X-Real-IP} 写入 {@link UserContext}。
 *
 * <p>内部 Token 一律来自 {@code codewise.internal-token} 配置（环境变量
 * {@code CODEWISE_INTERNAL_TOKEN}），无默认值——缺失时启动失败即暴露配置遗漏。
 * 请求头细节不落日志（历史版本曾全量打印请求头，含 Authorization 与内部 Token）。</p>
 */
@AutoConfiguration
public class UserAuthInterceptor implements HandlerInterceptor {
    /** 管理员角色标识 */
    private static final Integer ADMIN_ROLE = 2;
    /** 内部通信密钥：与网关配置保持一致，无默认值（缺失即启动失败） */
    @Value("${codewise.internal-token}")
    private String internalToken;
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/user/login",
            "/api/user/emaillogin",
            "/api/user/emailloginforcode",
            "/api/user/getemailcode",
            "/api/user/emailregister",
            "/api/user/register",
            "/api/user/updatepasswordforemail",
            "/api/user/updatefromcode",
            "/api/user/appeal/submit"
    );
    private static final Set<String> PUBLIC_PATH_PATTERNS = Set.of(
            "/uploads/**",
            "/websocket/**",
            "/ws/**",
            "/sockjs/**"
    );
    /** 存活探针路径：容器健康检查无内部 Token，必须在 Token 校验前放行（仅暴露 UP/DOWN 状态） */
    private static final Set<String> PROBE_PATHS = Set.of("/actuator/health", "/actuator/health/", "/actuator/info");
    AntPathMatcher antPathMatcher = new AntPathMatcher();
    @Lazy
    @Autowired(required = false)
    private UserFeignClient userFeignClient;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String ip = request.getHeader("X-Real-IP");
        if (ip != null) {
            UserContext.setCurrentIp(ip);
        }
        // 存活探针在内部 Token 校验之前放行，供容器编排 healthcheck 使用。
        if (PROBE_PATHS.contains(path)) {
            return true;
        }
        // 验证内部 Token 后，才允许进入任何匿名白名单。
        String headerToken = request.getHeader("X-Internal-Token");
        if (headerToken == null || !headerToken.equals(internalToken)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            try {
                response.getWriter().write("{\"code\":403,\"message\":\"禁止直接访问，请通过网关访问\"}");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        }

        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        if (PUBLIC_PATHS.contains(normalizedPath)
                || PUBLIC_PATH_PATTERNS.stream().anyMatch(pattern -> antPathMatcher.match(pattern, normalizedPath))) {
            return true;
        }

        // WebSocket 握手的 JWT 校验由网关和 WebSocket 握手拦截器共同完成。
        String upgrade = request.getHeader("Upgrade");
        if ("websocket".equalsIgnoreCase(upgrade)) {
            return true;
        }

        // 从请求头获取 userId（网关塞的）
        String userIdStr = request.getHeader("X-User-Id");
        if (userIdStr != null && !userIdStr.isEmpty()) {
            UserContext.setUserId(Long.parseLong(userIdStr));
        }

        // 4. 获取用户名（如果有）
        String userName = request.getHeader("X-User-Name");
        if (userName != null) {
            UserContext.setUserName(userName);
        }

        if(antPathMatcher.match(path,"/api/*/governance")){
            try{
            if(userIdStr == null){
                response.getWriter().write("{\"code\":401,\"message\":\"请登录后重试\"}");
                return false;
            }
            Result<UserDto>body=userFeignClient.getUserInfo(Long.parseLong(userIdStr));
            if(body==null||body.getData()==null){
                response.getWriter().write("{\"code\":401,\"message\":\"请登录后重试\"}");
                return false;
            }
            UserDto userDto=body.getData();
            if(userDto.getRoleId()!=ADMIN_ROLE){
                response.getWriter().write("{\"code\":403,\"message\":\"无权访问\"}");
                return false;
            }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        return true;
}
@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
}
}

package org.example.servicecommon.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

@AutoConfiguration
public class UserAuthInterceptor implements HandlerInterceptor {
    // 内部通信密钥（和网关保持一致）
    private static final String INTERNAL_TOKEN = "codewise-secret-2026";
    private static final Integer ADMIN_ROLE =2;
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
    private static final Set<String> PUBLIC_PATH_PATTERNS = Set.of(
            "/uploads/**",
            "/websocket/**",
            "/ws/**",
            "/sockjs/**"
    );
    AntPathMatcher antPathMatcher = new AntPathMatcher();
    @Lazy
    @Autowired(required = false)
    private UserFeignClient userFeignClient;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 打印所有请求头
        System.out.println("========== 所有请求头 ==========");
        java.util.Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            System.out.println(headerName + ": " + request.getHeader(headerName));
        }
        System.out.println("=================================");

        String path = request.getRequestURI();
        String ip = request.getHeader("X-Real-IP");
        System.out.println(request.getHeader("X-Real-IP"));
        System.out.println(request);
        if (ip != null) {
            UserContext.setCurrentIp(ip);
        }
        // 验证内部 Token 后，才允许进入任何匿名白名单。
        String internalToken = request.getHeader("X-Internal-Token");
        if (internalToken == null || !internalToken.equals(INTERNAL_TOKEN)) {
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
            System.out.println("✅ 放行已通过内部校验的 WebSocket 握手: " + path);
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

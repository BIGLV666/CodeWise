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

@AutoConfiguration
public class UserAuthInterceptor implements HandlerInterceptor {
    // 内部通信密钥（和网关保持一致）
    private static final String INTERNAL_TOKEN = "codewise-secret-2026";
    private static final Integer ADMIN_ROLE =2;
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
        // ====== 1. 放行 WebSocket 握手 ======
        String upgrade = request.getHeader("Upgrade");
        if ("websocket".equalsIgnoreCase(upgrade)) {
            System.out.println("✅ 放行 WebSocket 握手: " + path);
            return true;
        }

        // ====== 2. 放行 WebSocket 相关路径 ======
        if (path.startsWith("/websocket") || path.startsWith("/ws")) {
            System.out.println("✅ 放行 WebSocket 路径: " + path);
            return true;
        }

        // ====== 3. 放行 SockJS 相关路径 ======
        if (path.contains("/sockjs") || path.contains("/info")) {
            System.out.println("✅ 放行 SockJS: " + path);
            return true;
        }



        // 1. 登录/注册接口放行（不需要登录）
        if (path.contains("login") || path.contains("register")||path.contains("updatepasswordforemail")||path.contains("updateformcode")) {
            return true;
        }
        //2.放行静态资源
        if(path.contains("uploads")){
            return true;
        }

        // 2. 验证内部 Token（确保请求来自网关）
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

        // 3. 从请求头获取 userId（网关塞的）
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

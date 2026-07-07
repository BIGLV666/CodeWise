package org.example.servicereview.config;

import org.example.servicecommon.config.UserAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private UserAuthInterceptor userAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        System.out.println(registry);

        registry.addInterceptor(userAuthInterceptor)

         .addPathPatterns("/**")
                .excludePathPatterns(
                       "/websocket/**",      // ✅ 排除 WebSocket
                        "/ws/**",              // ✅ 排除 WebSocket
                       "/sockjs/**",          // ✅ 排除 SockJS
                        "/login",              // ✅ 排除登录
                        "/register"            // ✅ 排除注册
                );
    }
}

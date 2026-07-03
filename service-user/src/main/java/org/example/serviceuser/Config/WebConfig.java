package org.example.serviceuser.Config;




import org.example.servicecommon.config.UserAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Enumeration;

@Configuration
    public class WebConfig implements WebMvcConfigurer {


    @Autowired
    private UserAuthInterceptor userAuthInterceptor;
        @Override
        public void addInterceptors(InterceptorRegistry registry) {

            registry.addInterceptor(userAuthInterceptor)
                    .addPathPatterns("/**")                          // 拦截所有请求
                    .excludePathPatterns("/api/user/login")          // 放行登录
                    //.excludePathPatterns("/api/user/register")       // 放行注册
                    .excludePathPatterns("/api/user/wechat/callback")// 放行微信回调
                    //.excludePathPatterns("/api/user/emailregister")
            ;
            System.out.println("✅ 拦截器注册完成");
    }

    }


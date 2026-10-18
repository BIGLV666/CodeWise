package org.example.serviceuser.Config;


import org.example.servicecommon.config.UserAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
    public class WebConfig implements WebMvcConfigurer {


    @Autowired
    private UserAuthInterceptor userAuthInterceptor;

    /** 上传文件根目录（compose 经 FILE_UPLOAD_ROOT 注入，与 FileUploadService 同一处配置） */
    @Value("${file.upload.root}")
    private String uploadRoot;
        @Override
        public void addInterceptors(InterceptorRegistry registry) {

            registry.addInterceptor(userAuthInterceptor)
                    .addPathPatterns("/**")                          // 拦截所有请求
                    .excludePathPatterns("/api/user/login")          // 放行登录
                    //.excludePathPatterns("/api/user/register")       // 放行注册
                    .excludePathPatterns("/api/user/wechat/callback")
                    .excludePathPatterns("/api/user/updatefromcode")
                    .excludePathPatterns("/api/user/updatepasswordforemail")
                    .excludePathPatterns("/uploads/**");
                    //.excludePathPatterns("/api/user/emailregister")
            ;
            System.out.println("✅ 拦截器注册完成");
    } @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadLocation(uploadRoot));
    }

    /**
     * 上传目录对外暴露的静态资源位置。
     *
     * <p>必须跟随 {@code file.upload.root}（写入端 {@code FileUploadService} 用的也是它）：
     * 这里曾写死开发机路径 {@code file:E:/learncard/CodeWise/service-user/static/uploads/}，
     * 容器里文件实际落在 {@code /app/data/uploads}，于是头像、题目配图上传成功后一律 404。</p>
     *
     * @param root 上传根目录（绝对或相对路径均可，相对路径按进程工作目录解析）
     * @return Spring 资源位置字符串，形如 {@code file:/app/data/uploads/}
     */
    static String uploadLocation(String root) {
        return "file:" + Paths.get(root).toAbsolutePath().normalize() + "/";
    }

    }


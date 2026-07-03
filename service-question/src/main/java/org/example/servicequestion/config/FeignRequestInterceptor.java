package org.example.servicequestion.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.example.servicecommon.until.UserContext;
import org.springframework.stereotype.Component;

/**
 * Feign 请求拦截器
 * 将当前请求的用户信息（userId、userName、ip）添加到 Feign 调用时的请求头中
 * 传递给下游服务使用
 */
@Component
public class FeignRequestInterceptor implements RequestInterceptor {

    // 内部通信密钥（和网关/其他服务保持一致）
    private static final String INTERNAL_TOKEN = "codewise-secret-2026";

    @Override
    public void apply(RequestTemplate template) {
        // 添加内部通信密钥
        template.header("X-Internal-Token", INTERNAL_TOKEN);

        // 添加用户ID
        Long userId = UserContext.getUserId();
        if (userId != null) {
            template.header("X-User-Id", String.valueOf(userId));
        }

        // 添加用户名
        String userName = UserContext.getUserName();
        if (userName != null) {
            template.header("X-User-Name", userName);
        }

        // 添加真实IP
        String ip = UserContext.getIp();
        if (ip != null) {
            template.header("X-Real-IP", ip);
        }
    }
}

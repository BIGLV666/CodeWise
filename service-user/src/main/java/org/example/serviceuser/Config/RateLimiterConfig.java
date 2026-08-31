package org.example.serviceuser.Config;


import org.example.servicecommon.until.UserContext;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class RateLimiterConfig {
    @Bean
    @ConditionalOnMissingBean(RateLimitKeyResolver.class)
    public RateLimitKeyResolver legacyUserRateLimitKeyResolver() {
        return context -> {
            Long userId = UserContext.getUserId();

            // 未登录接口：按 IP 限流，放宽限制（IP 是共享的）
            if (userId == null) {
                String ip = getClientIp();

                // 动态调整 IP 限流阈值：如果注解设置了限流，则放大 20 倍
                // 例如：@RateLimit(limit=5) 对用户是 5 次，对 IP 是 100 次
                if (context.isRateLimitEnabled()) {
                    int originalLimit = context.getRateLimit();
                    long ipLimit = (long) originalLimit * 20; // IP 限流倍数
                    context.setRateLimit((int) Math.min(Integer.MAX_VALUE,
                            Math.max(originalLimit, ipLimit)));
                }

                return context.getApiKey() + "#ip:" + ip;
            }

            // 已登录接口：按用户 ID 限流（保持注解原值）
            return context.getApiKey() + "#user:" + userId;
        };
    }

    /**
     * 获取客户端真实 IP（支持代理/负载均衡）
     */
    private String getClientIp() {
        String contextIp = UserContext.getIp();
        if (contextIp != null && !contextIp.isBlank()) {
            return contextIp;
        }
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }

        HttpServletRequest request = attrs.getRequest();

        // 优先从代理头获取真实 IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能有多个 IP，取第一个
            return ip.split(",")[0].trim();
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        // 兜底：直接获取远程地址
        return request.getRemoteAddr();
    }
}

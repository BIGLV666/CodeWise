package org.example.servicecommon.config;

import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import jakarta.servlet.http.HttpServletRequest;

import org.example.servicecommon.until.UserContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * CodeWise 统一限流键策略。
 *
 * <p>登录用户按用户 ID 限流，避免同一用户通过更换 IP 绕过配额；
 * 未登录请求按 IP 限流，并放大注解配额，避免公司网络、校园网等共享出口被少数用户误伤。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    private static final int ANONYMOUS_LIMIT_MULTIPLIER = 20;

    @Bean
    @ConditionalOnMissingBean(RateLimitKeyResolver.class)
    public RateLimitKeyResolver userRateLimitKeyResolver() {
        return context -> {
            Long userId = UserContext.getUserId();
            if (userId == null) {
                String ip = getClientIp();
                if (context.isRateLimitEnabled()) {
                    int originalLimit = context.getRateLimit();
                    long expandedLimit = (long) originalLimit * ANONYMOUS_LIMIT_MULTIPLIER;
                    // 防止异常配置导致 int 溢出，至少保持原始配额。
                    context.setRateLimit((int) Math.min(Integer.MAX_VALUE,
                            Math.max(originalLimit, expandedLimit)));
                }
                return context.getApiKey() + "#ip:" + ip;
            }
            return context.getApiKey() + "#user:" + userId;
        };
    }

    /** 获取网关传入的真实 IP，直接访问服务时再从 Servlet 请求中兜底。 */
    private String getClientIp() {
        String contextIp = UserContext.getIp();
        if (contextIp != null && !contextIp.isBlank()) {
            return contextIp;
        }

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = firstForwardedIp(request.getHeader("X-Forwarded-For"));
        if (ip != null) {
            return ip;
        }
        ip = normalizeIp(request.getHeader("X-Real-IP"));
        return ip != null ? ip : normalizeIp(request.getRemoteAddr(), "unknown");
    }

    private String firstForwardedIp(String value) {
        String normalized = normalizeIp(value);
        if (normalized == null) {
            return null;
        }
        int comma = normalized.indexOf(',');
        return comma >= 0 ? normalized.substring(0, comma).trim() : normalized;
    }

    private String normalizeIp(String value) {
        return normalizeIp(value, null);
    }

    private String normalizeIp(String value, String fallback) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) {
            return fallback;
        }
        return value.trim();
    }
}

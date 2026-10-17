package org.example.servicecommon.config;

import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import jakarta.servlet.http.HttpServletRequest;

import org.example.servicecommon.until.UserContext;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
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
 *
 * <p><b>{@code @AutoConfigureBefore} 不可删除：</b>本类与 starter 的
 * {@link ApiGovernanceAutoConfiguration} 都通过 {@code AutoConfiguration.imports} 注册，
 * 两者的 {@code @ConditionalOnMissingBean} 谁先生效取决于自动配置排序（默认为类名字母序，
 * {@code io.github...} 会排在 {@code org.example...} 之前）。没有这个 before，
 * starter 的 {@code DefaultRateLimitKeyResolver}（接口级、全体用户共享配额）会先注册，
 * 本 Bean 静默失效——限流键退化为 apiKey，既不区分用户也没有匿名放大。</p>
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore(ApiGovernanceAutoConfiguration.class)
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

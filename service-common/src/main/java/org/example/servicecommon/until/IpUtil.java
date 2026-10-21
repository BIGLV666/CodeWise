package org.example.servicecommon.until;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class IpUtil {
    /** 获取网关传入的真实 IP，直接访问服务时再从 Servlet 请求中兜底。 */
    public static String getClientIp() {
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

    private static String firstForwardedIp(String value) {
        String normalized = normalizeIp(value);
        if (normalized == null) {
            return null;
        }
        int comma = normalized.indexOf(',');
        return comma >= 0 ? normalized.substring(0, comma).trim() : normalized;
    }

    private static String normalizeIp(String value) {
        return normalizeIp(value, null);
    }

    private static String normalizeIp(String value, String fallback) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) {
            return fallback;
        }
        return value.trim();
    }
}



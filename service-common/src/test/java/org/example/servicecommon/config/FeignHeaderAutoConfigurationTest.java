package org.example.servicecommon.config;

import feign.RequestTemplate;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 统一 Feign 请求头拦截器单元测试：验证身份头透传与内部 Token 注入。
 */
class FeignHeaderAutoConfigurationTest {

    private final FeignHeaderAutoConfiguration configuration = new FeignHeaderAutoConfiguration();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 应注入内部Token与完整身份头() {
        UserContext.setUserId(42L);
        UserContext.setUserName("lv");
        UserContext.setCurrentIp("10.0.0.1");

        RequestTemplate template = new RequestTemplate();
        configuration.commonFeignRequestInterceptor("test-token").apply(template);

        assertEquals("test-token", template.headers().get("X-Internal-Token").iterator().next());
        assertEquals("42", template.headers().get("X-User-Id").iterator().next());
        assertEquals("lv", template.headers().get("X-User-Name").iterator().next());
        assertEquals("10.0.0.1", template.headers().get("X-Real-IP").iterator().next());
    }

    @Test
    void 无用户上下文时只注入内部Token() {
        RequestTemplate template = new RequestTemplate();
        configuration.commonFeignRequestInterceptor("test-token").apply(template);

        assertEquals("test-token", template.headers().get("X-Internal-Token").iterator().next());
        assertNull(template.headers().get("X-User-Id"));
        assertNull(template.headers().get("X-User-Name"));
        assertNull(template.headers().get("X-Real-IP"));
    }
}

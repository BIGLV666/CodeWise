package org.example.servicecommon.until;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UserContext} 是 ThreadLocal 容器，{@code clear()} 必须清干净全部身份字段：
 * 少清一项（尤其是登出黑名单依赖的 token）都会在 Tomcat 线程复用时串到下一个请求。
 */
class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void clearRemovesEveryIdentityFieldIncludingToken() {
        UserContext.setUserId(42L);
        UserContext.setUserName("lv");
        UserContext.setCurrentIp("10.0.0.1");
        UserContext.setUserToken("header.payload.signature");

        UserContext.clear();

        assertNull(UserContext.getUserId());
        assertNull(UserContext.getUserName());
        assertNull(UserContext.getIp());
        assertNull(UserContext.getToken());
    }

    @Test
    void tokenIsReadBackInTheSameThread() {
        UserContext.setUserToken("header.payload.signature");

        assertEquals("header.payload.signature", UserContext.getToken());
    }
}

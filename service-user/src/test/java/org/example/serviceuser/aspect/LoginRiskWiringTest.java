package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import org.example.serviceuser.controller.UserController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录风控接线守卫：
 * 1. 风控 handler 不能再注册 {@code @AsyncHandler}——登录已改走同步后置链，
 *    异步再记一次会让 5 次阈值变成 3 次；{@code @AsyncAction} 仍保留给欢迎邮件等
 *    其它异步监听者使用；
 * 2. 前置/后置链必须覆盖用户名与邮箱两个密码登录入口，避免冻结后切换入口绕过。
 */
class LoginRiskWiringTest {

    @Test
    void riskHandlersMustNotRegisterAsyncHandlers() {
        int asyncHandlers = 0;
        for (Method method : AccountProtectionAsyncHandlers.class.getDeclaredMethods()) {
            asyncHandlers += method.getAnnotationsByType(AsyncHandler.class).length;
        }
        assertEquals(0, asyncHandlers,
                "同步链路已直接调用 handler，重复注册 @AsyncHandler 会让同一次失败被记两次");
    }

    @Test
    void loginActionStaysForOtherAsyncListeners() throws Exception {
        Method login = UserController.class.getMethod("login", String.class, String.class);
        AsyncAction action = login.getAnnotation(AsyncAction.class);
        assertNotNull(action, "欢迎邮件等异步监听仍依赖 user-login action，不能删除");
        assertEquals("user-login", action.value());
    }

    @Test
    void bothPasswordLoginEntriesAreCovered() {
        assertTrue(AccountProtection.LOGIN_METHODS.contains("login"));
        assertTrue(AccountProtection.LOGIN_METHODS.contains("emailLogin"));
        assertTrue(LoginFreezePreFilter.LOGIN_METHODS.contains("login"));
        assertTrue(LoginFreezePreFilter.LOGIN_METHODS.contains("emailLogin"));
    }
}

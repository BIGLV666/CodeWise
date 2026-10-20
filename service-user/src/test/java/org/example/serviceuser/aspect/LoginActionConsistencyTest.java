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
 * api-governance 启动时会校验 @AsyncHandler 引用的 action 必须存在对应的 @AsyncAction。
 * 这个测试把「handler 写了 user.login、controller 写了 user-login」这类拼写错误挡在启动之前。
 */
class LoginActionConsistencyTest {

    @Test
    void accountProtectionHandlersUseControllerAction() throws Exception {
        Method login = UserController.class.getMethod("login", String.class, String.class);
        AsyncAction action = login.getAnnotation(AsyncAction.class);
        assertNotNull(action, "UserController.login 必须声明 @AsyncAction");

        int handlerCount = 0;
        for (Method method : AccountProtectionAsyncHandlers.class.getDeclaredMethods()) {
            for (AsyncHandler handler : method.getAnnotationsByType(AsyncHandler.class)) {
                handlerCount++;
                assertEquals(action.value(), handler.value(),
                        method.getName() + " 的 @AsyncHandler action 与 controller 不一致");
            }
        }
        assertTrue(handlerCount >= 2, "登录风控应至少注册成功/失败两个 handler");
    }
}

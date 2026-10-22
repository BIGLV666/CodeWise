package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.async.AsyncInvocation;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.internal.AsyncEventFactory;
import io.github.biglv666.apigovernance.async.spi.AsyncEventEnricher;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceuser.controller.UserController;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证登录事件 enrichment：失败阶段只带账号，成功阶段带 userId，密码永不进入事件。
 */
class AsyncEnricherTest {

    private final AsyncEventEnricher enricher = new AsyncEnricher().login();
    private final AsyncEventFactory factory = new AsyncEventFactory(List.of(enricher));

    @Test
    void failureEventCarriesUsernameButNeverPassword() throws Exception {
        Method login = UserController.class.getMethod("login", String.class, String.class);
        AsyncInvocation invocation = new AsyncInvocation(
                "event-1", "user-login", AsyncPhase.AFTER_ERROR,
                UserController.class, login,
                new Object[]{"alice", "super-secret"},
                null,
                new BizException(UserErrorCode.LOGIN_FAILED),
                Instant.now(), 0L);

        AsyncEvent event = factory.create(invocation);

        assertEquals("alice", event.data().get("username"));
        assertFalse(event.data().containsKey("password"));
        assertFalse(event.data().containsValue("super-secret"));
    }

    @Test
    void successEventCarriesUserIdAndUsername() throws Exception {
        Method login = UserController.class.getMethod("login", String.class, String.class);
        User user = User.builder().userId(42L).userName("alice").email("alice@example.com").build();
        Map<String, Object> body = new HashMap<>();
        body.put("user", new UserDto(user));
        body.put("token", "jwt-token");

        AsyncInvocation invocation = new AsyncInvocation(
                "event-2", "user-login", AsyncPhase.AFTER_SUCCESS,
                UserController.class, login,
                new Object[]{"alice", "super-secret"},
                Result.success(body),
                null,
                Instant.now(), 0L);

        AsyncEvent event = factory.create(invocation);

        assertEquals(42L, event.data().get("userId"));
        assertEquals("alice", event.data().get("username"));
        assertFalse(event.data().containsKey("password"));
        assertFalse(event.data().containsValue("super-secret"));
    }

    @Test
    void otherActionsAreNotEnriched() throws Exception {
        Method login = UserController.class.getMethod("login", String.class, String.class);
        AsyncInvocation invocation = new AsyncInvocation(
                "event-3", "other-action", AsyncPhase.AFTER_ERROR,
                UserController.class, login,
                new Object[]{"alice", "super-secret"},
                null,
                new BizException(UserErrorCode.LOGIN_FAILED),
                Instant.now(), 0L);

        AsyncEvent event = factory.create(invocation);

        assertTrue(event.data().isEmpty());
    }
}

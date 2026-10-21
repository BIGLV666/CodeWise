package org.example.serviceuser.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceuser.controller.UserController;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步后置链的分流单测：登录成功/失败、非登录方法、空上下文与异常类型。
 */
@ExtendWith(MockitoExtension.class)
class AccountProtectionTest {

    private static final long USER_ID = 42L;

    @Mock private AccountProtectionAsyncHandlers handlers;
    @Mock private LoginUserMetaCache metaCache;
    @Mock private FilterContext context;

    private AccountProtection protection;
    private Method login;
    private Method emailLogin;

    @BeforeEach
    void setUp() throws Exception {
        protection = new AccountProtection(handlers, metaCache);
        login = UserController.class.getMethod("login", String.class, String.class);
        emailLogin = UserController.class.getMethod("emailLogin", String.class, String.class);
    }

    @Test
    void nullContextIsIgnored() {
        assertDoesNotThrow(() -> protection.doFilter(null));
    }

    @Test
    void nullMethodIsIgnored() {
        when(context.getMethod()).thenReturn(null);

        assertDoesNotThrow(() -> protection.doFilter(context));
        verify(handlers, never()).loginFailure(anyString());
    }

    @Test
    void nonLoginMethodIsIgnored() {
        Method method = org.mockito.Mockito.mock(Method.class);
        when(method.getName()).thenReturn("logout");
        when(context.getMethod()).thenReturn(method);

        protection.doFilter(context);

        verify(handlers, never()).loginFailure(anyString());
        verify(handlers, never()).loginSuccess(any(), anyString());
    }

    @Test
    void loginFailureCountsAccount() {
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(new BizException(UserErrorCode.LOGIN_FAILED));
        when(context.getArgs()).thenReturn(new Object[]{"alice", "pwd"});

        protection.doFilter(context);

        verify(handlers).loginFailure("alice");
    }

    @Test
    void emailLoginFailureAlsoCounts() {
        when(context.getMethod()).thenReturn(emailLogin);
        when(context.getError()).thenReturn(new BizException(UserErrorCode.LOGIN_FAILED));
        when(context.getArgs()).thenReturn(new Object[]{"alice@example.com", "pwd"});

        protection.doFilter(context);

        verify(handlers).loginFailure("alice@example.com");
    }

    @Test
    void otherBizErrorIsNotCounted() {
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(new BizException(UserErrorCode.CODE_INCORRECT));

        protection.doFilter(context);

        verify(handlers, never()).loginFailure(anyString());
    }

    @Test
    void nonBizErrorIsNotCounted() {
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(new IllegalStateException("boom"));

        protection.doFilter(context);

        verify(handlers, never()).loginFailure(anyString());
    }

    @Test
    void successUnwrapsResultAndRefreshesCache() {
        UserDto dto = new UserDto(user());
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(null);
        when(context.getResult()).thenReturn(Result.success(Map.of("user", dto)));

        protection.doFilter(context);

        verify(metaCache).put(USER_ID, 1, "alice", "alice@example.com");
        verify(handlers).loginSuccess(USER_ID, "alice");
    }

    @Test
    void successWithUnexpectedResultShapeIsIgnored() {
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(null);
        when(context.getResult()).thenReturn(Result.success("ok"));

        protection.doFilter(context);

        verify(handlers, never()).loginSuccess(any(), anyString());
        verify(metaCache, never()).put(any(), any(), any());
    }

    @Test
    void successWithNullResultIsIgnored() {
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(null);
        when(context.getResult()).thenReturn(null);

        assertDoesNotThrow(() -> protection.doFilter(context));
        verify(handlers, never()).loginSuccess(any(), anyString());
    }

    @Test
    void successWithMalformedUserIdIsIgnored() {
        // 模拟脏数据：Result 结构正确但 userId 不是数字
        UserDto dto = org.mockito.Mockito.mock(UserDto.class);
        when(dto.getUserId()).thenReturn("not-a-number");
        when(context.getMethod()).thenReturn(login);
        when(context.getError()).thenReturn(null);
        when(context.getResult()).thenReturn(Result.success(Map.of("user", dto)));

        assertDoesNotThrow(() -> protection.doFilter(context));
        verify(handlers, never()).loginSuccess(any(), anyString());
    }

    private static User user() {
        return User.builder()
                .userId(USER_ID)
                .userName("alice")
                .email("alice@example.com")
                .status(1)
                .build();
    }
}

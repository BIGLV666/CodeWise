package org.example.servicejudge.controller;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.aop.AdminAuthAspect;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DockerController 管理员鉴权守护测试。
 *
 * <p>覆盖两层防线：</p>
 * <ul>
 *   <li>反射守护：控制器类级必须持续标注 {@link RequireAdmin}，防止重构时注解丢失；</li>
 *   <li>行为验证：{@link AdminAuthAspect} 按 PreFilter 契约拒绝（setRejectReason + 返回 false），
 *       覆盖未登录、普通用户、管理员、root、用户不存在与无注解放行分支。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DockerControllerAdminAuthTest {

    /** 被校验的目标方法：容器池列表接口（声明类必须为 DockerController，切面按声明类读取注解）。 */
    private static final Method LIST_CONTAINERS = declaredListMethod();

    @Mock
    private UserFeignClient userFeignClient;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Method declaredListMethod() {
        try {
            return DockerController.class.getDeclaredMethod("getContainers");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造被测切面并注入 Feign 依赖（生产环境由自动装配注册）。 */
    private AdminAuthAspect newAspect() {
        AdminAuthAspect aspect = new AdminAuthAspect();
        ReflectionTestUtils.setField(aspect, "userFeignClient", userFeignClient);
        return aspect;
    }

    /** 构造携带指定方法的过滤上下文（切面只读取 method 与写入 rejectReason）。 */
    private FilterContext contextOf(Method method) {
        FilterContext context = mock(FilterContext.class);
        lenient().when(context.getMethod()).thenReturn(method);
        return context;
    }

    /** 让切面通过 Feign 查到指定角色的用户。 */
    private void stubUser(int roleId) {
        UserDto user = new UserDto();
        user.setRoleId(roleId);
        when(userFeignClient.getUserInfo(UserContext.getUserId())).thenReturn(Result.success(user));
    }

    @Test
    void dockerControllerIsClassLevelAdminGuarded() {
        assertNotNull(DockerController.class.getAnnotation(RequireAdmin.class),
                "DockerController 必须保持类级 @RequireAdmin，否则容器池接口对普通用户开放");
    }

    @Test
    void blocksAnonymousRequest() {
        AdminAuthAspect aspect = newAspect();
        FilterContext context = contextOf(LIST_CONTAINERS);
        assertFalse(aspect.doFilter(context));
        verify(context).setRejectReason("请登录后重试");
    }

    @Test
    void blocksNormalUser() {
        UserContext.setUserId(1001L);
        stubUser(1);
        FilterContext context = contextOf(LIST_CONTAINERS);
        assertFalse(newAspect().doFilter(context));
        verify(context).setRejectReason("无权访问");
    }

    @Test
    void blocksNullRoleIdAsNormalUser() {
        UserContext.setUserId(1006L);
        stubUser(1);
        // roleId 为 null 的异常账号按普通用户拒绝，避免拆箱 NPE
        UserDto user = new UserDto();
        user.setRoleId(null);
        when(userFeignClient.getUserInfo(1006L)).thenReturn(Result.success(user));
        FilterContext context = contextOf(LIST_CONTAINERS);
        assertFalse(newAspect().doFilter(context));
        verify(context).setRejectReason("无权访问");
    }

    @Test
    void blocksWhenUserRecordMissing() {
        UserContext.setUserId(1002L);
        when(userFeignClient.getUserInfo(1002L)).thenReturn(Result.success(null));
        FilterContext context = contextOf(LIST_CONTAINERS);
        assertFalse(newAspect().doFilter(context));
        verify(context).setRejectReason("用户不存在");
    }

    @Test
    void allowsAdminUser() {
        UserContext.setUserId(1003L);
        stubUser(2);
        assertTrue(newAspect().doFilter(contextOf(LIST_CONTAINERS)));
        // 放行时不得写入拒绝原因
        verify(contextOf(LIST_CONTAINERS), never()).setRejectReason(anyString());
        verify(userFeignClient).getUserInfo(any());
    }

    @Test
    void allowsRootUser() {
        UserContext.setUserId(1004L);
        stubUser(0);
        assertTrue(newAspect().doFilter(contextOf(LIST_CONTAINERS)));
    }

    @Test
    void passesThroughWhenNoAnnotation() {
        UserContext.setUserId(1005L);
        // 声明类无 @RequireAdmin 的方法（如 Object.toString）应直接放行，不触发 Feign 查询
        Method plainMethod;
        try {
            plainMethod = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(newAspect().doFilter(contextOf(plainMethod)));
        verifyNoUserLookup();
    }

    private void verifyNoUserLookup() {
        verify(userFeignClient, never()).getUserInfo(any());
    }
}

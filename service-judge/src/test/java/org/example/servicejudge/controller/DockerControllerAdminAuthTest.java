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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DockerController 管理员鉴权守护测试。
 *
 * <p>覆盖两层防线：</p>
 * <ul>
 *   <li>反射守护：控制器类级必须持续标注 {@link RequireAdmin}，防止重构时注解丢失；</li>
 *   <li>行为验证：{@link AdminAuthAspect} 对容器池接口的未登录、普通用户、管理员、
 *       root、用户不存在与无注解放行分支（aspect 在 service-common 中经
 *       AutoConfiguration.imports 注册，此处直接实例化验证判定逻辑本身）。</li>
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

    /** 构造携带指定方法的过滤上下文（切面只读取 method）。 */
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
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> aspect.doFilter(contextOf(LIST_CONTAINERS)));
        assertEquals("请登录后重试", e.getMessage());
    }

    @Test
    void blocksNormalUser() {
        UserContext.setUserId(1001L);
        stubUser(1);
        AdminAuthAspect aspect = newAspect();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> aspect.doFilter(contextOf(LIST_CONTAINERS)));
        assertEquals("无权访问", e.getMessage());
    }

    @Test
    void blocksWhenUserRecordMissing() {
        UserContext.setUserId(1002L);
        when(userFeignClient.getUserInfo(1002L)).thenReturn(Result.success(null));
        AdminAuthAspect aspect = newAspect();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> aspect.doFilter(contextOf(LIST_CONTAINERS)));
        assertEquals("用户不存在", e.getMessage());
    }

    @Test
    void allowsAdminUser() {
        UserContext.setUserId(1003L);
        stubUser(2);
        assertTrue(newAspect().doFilter(contextOf(LIST_CONTAINERS)));
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
    }
}

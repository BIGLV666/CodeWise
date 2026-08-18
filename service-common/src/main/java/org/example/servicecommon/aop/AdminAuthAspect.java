package org.example.servicecommon.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.example.apigovernancespringbootstarter.filter.FilterContext;
import org.example.apigovernancespringbootstarter.filter.PreFilter;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 管理员权限校验切面。
 * <p>拦截标注了 {@link org.example.servicecommon.aop.RequireAdmin} 的方法（方法级），
 * 以及位于标注了该注解的类中的方法（类级）。</p>
 * <p>身份来自 {@link UserContext#getUserId()}（由 UserAuthInterceptor 写入），
 * 再回查用户的 roleId 判定是否为管理员（roleId == 2）。
 * 校验失败抛出 RuntimeException，由各 controller 的 GlobalExceptionHandler 统一返回 Result.error。</p>
 */
@Slf4j
@Order(2)
@Component
public class AdminAuthAspect implements PreFilter {

    /** 管理员角色标识：1-普通用户 2-管理员 */
    private static final int ROLE_ADMIN = 2;

    @Autowired(required = false)
    private UserFeignClient userFeignClient;

    @Override
    public boolean doFilter(FilterContext context) {

        Method method=context.getMethod();
        Class<?> clazz=method.getDeclaringClass();
        RequireAdmin admin;
        admin=clazz.getAnnotation(RequireAdmin.class);
        if (admin == null) {
            admin=method.getAnnotation(RequireAdmin.class);
        }
        if (admin == null) {
            return true;
        }
        Long userId=UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后重试");
        }
        Result<UserDto> userBody = userFeignClient.getUserInfo(userId);
        if (userBody == null || userBody.getData() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        UserDto user=userBody.getData();
        if(user.getRoleId()!=ROLE_ADMIN){
            log.warn("非管理员用户尝试访问管理接口, userId={}", userId);
            throw new IllegalArgumentException("无权访问");
        }
        return true;
    }
}

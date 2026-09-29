package org.example.servicecommon.aop;

import lombok.extern.slf4j.Slf4j;
import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import io.github.biglv666.apigovernance.filter.PreFilter;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 管理员权限校验切面。
 * <p>拦截标注了 {@link org.example.servicecommon.aop.RequireAdmin} 的方法（方法级），
 * 以及位于标注了该注解的类中的方法（类级）。本切面经
 * {@code META-INF/spring/...AutoConfiguration.imports} 注册，所有引入 service-common 的服务自动生效。</p>
 * <p>身份来自 {@link UserContext#getUserId()}（由 UserAuthInterceptor 写入），
 * 再经 Feign 回查用户的 roleId 判定是否为管理员（roleId == 2）或 root（roleId == 0）。</p>
 * <p>遵循 PreFilter 契约：拒绝时通过 {@link FilterContext#setRejectStatus(int)} 与
 * {@link FilterContext#setRejectReason(String)} 设置状态与原因并返回 {@code false}，
 * 由 api-governance 抛出 {@code GovernanceException}、再交各服务的
 * {@code AbstractGlobalExceptionHandler} 统一转换为拒绝响应；不要在切面内抛异常——
 * 过滤器链会把异常包装成无业务含义的「过滤器异常: xxx」。</p>
 */
@Slf4j
@Order(2)
@Component
public class AdminAuthAspect implements PreFilter {

    /** 管理员角色标识：1-普通用户 2-管理员 0-root */
    private static final int ROLE_ADMIN = 2;
    private static final int ROLE_ROOT = 0;

    @Autowired(required = false)
    private UserFeignClient userFeignClient;

    @Override
    public boolean doFilter(FilterContext context) {

        Method method = context.getMethod();
        Class<?> clazz = method.getDeclaringClass();
        RequireAdmin admin = clazz.getAnnotation(RequireAdmin.class);
        if (admin == null) {
            admin = method.getAnnotation(RequireAdmin.class);
        }
        if (admin == null) {
            return true;
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            reject(context, HttpStatus.UNAUTHORIZED, CommonErrorCode.LOGIN_REQUIRED);
            return false;
        }
        Result<UserDto> userBody = userFeignClient.getUserInfo(userId);
        if (userBody == null || userBody.getData() == null) {
            log.warn("管理接口鉴权回查用户失败, userId={}", userId);
            reject(context, HttpStatus.UNAUTHORIZED, CommonErrorCode.LOGIN_REQUIRED);
            return false;
        }
        UserDto user = userBody.getData();
        if (user.getRoleId() == null || (user.getRoleId() != ROLE_ADMIN && user.getRoleId() != ROLE_ROOT)) {
            log.warn("非管理员用户尝试访问管理接口, userId={}", userId);
            reject(context, HttpStatus.FORBIDDEN, CommonErrorCode.ADMIN_ONLY);
            return false;
        }
        return true;
    }

    /**
     * 设置拒绝原因与状态。
     *
     * <p>{@code FilterContext.rejectStatus} 默认是 429（面向限流），本切面必须显式改写，
     * 否则「未登录 / 非管理员」会被 {@code AbstractGlobalExceptionHandler} 归一成
     * 频率限制码 4290001，与文案语义不符。</p>
     */
    private void reject(FilterContext context, HttpStatus status, ErrorCode errorCode) {
        context.setRejectStatus(status.value());
        context.setRejectReason(errorCode.getMessage());
    }
}

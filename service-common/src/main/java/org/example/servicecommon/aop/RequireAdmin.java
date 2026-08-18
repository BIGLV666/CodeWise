package org.example.servicecommon.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员权限校验注解。
 * <p>标在方法上：仅该方法需要管理员身份。</p>
 * <p>标在类上：该类内所有 public 方法都需要管理员身份（方法上再单独标注可叠加，效果一致）。</p>
 * 校验逻辑见 AdminAuthAspect。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}

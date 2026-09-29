package org.example.serviceuser.Advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-user 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}，本类只声明作用包。
 *
 * <p>原先的 NullPointerException / IllegalAccessError / RuntimeException 分支会把
 * {@code e.getMessage()} 原样返回给前端（JVM 提示、SQL、下游报错因此泄漏），已随
 * 基类接入一并移除：只有 BizException 的消息面向用户原样返回，其余异常统一换成
 * 通用中文文案，原始信息只进服务端日志。</p>
 */
@RestControllerAdvice(basePackages = "org.example.serviceuser")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

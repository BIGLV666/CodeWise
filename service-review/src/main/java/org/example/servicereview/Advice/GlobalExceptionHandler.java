package org.example.servicereview.Advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-review 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}，本类只声明作用包。
 *
 * <p>原实现的 NullPointerException / IllegalAccessError / RuntimeException 分支会把
 * {@code e.getMessage()} 原样拼进「系统繁忙」返回前端（内部信息泄漏点），已删除；
 * 未识别异常统一由基类兜底为通用文案，原始信息只进服务端日志。</p>
 */
@RestControllerAdvice(basePackages = "org.example.servicereview.controller")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

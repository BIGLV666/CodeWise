package org.example.servicecommunity.Advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-community 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}（BizException 原样返回、治理异常按状态归一、
 * 参数/数据访问/状态冲突/兜底统一错误码），本类只声明作用包。
 */
@RestControllerAdvice(basePackages = "org.example.servicecommunity.controller")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

package org.example.servicequestion.Advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-question 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}，本类只声明作用包。
 */
@RestControllerAdvice(basePackages = "org.example.servicequestion.controller")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

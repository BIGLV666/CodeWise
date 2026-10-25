package com.example.servicequiznest.advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-quiz-nest 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}，本类只声明作用包。
 *
 * <p>basePackages 必须是本服务的真实包名（历史写成 {@code org.example.servicequiznest.controller}
 * 与包结构不符，advice 永远不会生效，BizException 会退化成 500 通用文案）。内部 Feign 控制器
 * 位于 {@code com.example.servicequiznest.feign.controller}，也必须一起覆盖。</p>
 */
@RestControllerAdvice(basePackages = {
        "com.example.servicequiznest.controller",
        "com.example.servicequiznest.feign.controller"
})
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

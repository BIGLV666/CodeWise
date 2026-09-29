package org.example.servicejudge.Advice;

import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-judge 全局异常处理：映射规则全部来自
 * {@link AbstractGlobalExceptionHandler}，本类只声明作用包。
 *
 * <p>原「RuntimeException 分支原样透传 e.getMessage()」已删除：判题管理接口
 * 不再泄漏内部文案，只有 {@code BizException} 的枚举消息面向用户。</p>
 *
 * <p>MQ 消费链路（提交/重试/死信消费者）不经过本 advice：handler 抛出的
 * BizException 仍按原语义触发 nack/延迟重试/DLQ。</p>
 */
@RestControllerAdvice(basePackages = "org.example.servicejudge.controller")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {
}

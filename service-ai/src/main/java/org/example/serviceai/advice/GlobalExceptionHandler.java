package org.example.serviceai.advice;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.transport.AiProviderHttpException;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.advice.AbstractGlobalExceptionHandler;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * service-ai 全局异常处理：通用映射（BizException、参数校验、数据访问、兜底等）
 * 全部继承 {@link AbstractGlobalExceptionHandler}，本类只补充服务商调用失败分支。
 *
 * <p>AiProviderHttpException 继承 BizException，消息已是枚举中文文案，这里只额外
 * 把诊断信息写入日志；原始上游报错不进响应。</p>
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.example.serviceai.controller")
public class GlobalExceptionHandler extends AbstractGlobalExceptionHandler {

    @ExceptionHandler(AiProviderHttpException.class)
    public Result<?> handleProviderException(AiProviderHttpException exception) {
        log.warn("AI 服务商调用被拒绝: statusCode={}, diagnostic={}",
                exception.getStatusCode(), exception.getDiagnostic());
        return Result.error(exception.getMessage(), exception.getCode());
    }
}

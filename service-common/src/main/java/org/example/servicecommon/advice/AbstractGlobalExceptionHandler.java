package org.example.servicecommon.advice;

import io.github.biglv666.apigovernance.exception.GovernanceException;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 各服务 {@code @RestControllerAdvice} 的公共基类：各服务子类只需声明 basePackages
 * 与自有异常分支，其余映射沿用本类。
 *
 * <p>收敛原则：只有 {@link BizException} 的消息面向用户原样返回；其余异常（含
 * NullPointerException、IllegalStateException、Feign/IO 报错）一律换成通用中文文案，
 * 原始信息只进服务端日志，避免 JVM 提示或 SQL 泄漏到前端。</p>
 *
 * <p>HTTP 状态码保持 200，失败与否由 {@code Result.code} 表达（前端/agent 均按
 * {@code code !== 200} 分支）。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public abstract class AbstractGlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> handleBiz(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getMessage(), e.getCode());
    }

    /** api-governance 过滤器链的拒绝响应（限流、管理员校验等），文案本身面向用户。 */
    @ExceptionHandler(GovernanceException.class)
    public Result<?> handleGovernance(GovernanceException e) {
        log.warn("治理过滤器拒绝: status={}, code={}, message={}",
                e.getStatus(), e.getCode(), e.getMessage());
        return Result.error(e.getMessage(), governanceCode(e.getStatus()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage(), e);
        return Result.error(CommonErrorCode.PARAM_INVALID);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<?> handleValidation(BindException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return Result.error(CommonErrorCode.PARAM_INVALID);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(CommonErrorCode.PARAM_MISSING);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不符: {}", e.getName());
        return Result.error(CommonErrorCode.PARAM_INVALID);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(CommonErrorCode.BODY_UNREADABLE);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<?> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.error(CommonErrorCode.DUPLICATE_RESOURCE);
    }

    @ExceptionHandler(DataAccessException.class)
    public Result<?> handleDataAccess(DataAccessException e) {
        log.error("数据访问异常: {}", e.getMessage(), e);
        return Result.error(CommonErrorCode.DATA_OPERATION_FAILED);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result<?> handleIllegalState(IllegalStateException e) {
        log.error("状态冲突: {}", e.getMessage(), e);
        return Result.error(CommonErrorCode.STATE_CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleUnknown(Exception e) {
        log.error("未处理异常: {}", e.getMessage(), e);
        return Result.error(CommonErrorCode.INTERNAL_ERROR);
    }

    /** 过滤器拒绝码归一到本平台的错误码段位。 */
    private int governanceCode(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return CommonErrorCode.LOGIN_REQUIRED.getCode();
        }
        if (status == HttpStatus.FORBIDDEN.value()) {
            return CommonErrorCode.NO_PERMISSION.getCode();
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return CommonErrorCode.RATE_LIMITED.getCode();
        }
        return CommonErrorCode.NO_PERMISSION.getCode();
    }
}

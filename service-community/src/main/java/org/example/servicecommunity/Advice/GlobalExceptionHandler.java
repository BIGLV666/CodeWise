package org.example.servicecommunity.Advice;

import io.github.biglv666.apigovernance.exception.GovernanceException;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务错误消息原样返回；数据访问异常（含 SQL）与未知异常
 * 只回传通用提示，详情仅记服务端日志，避免内部实现泄漏到前端。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.example.servicecommunity.controller")
public class GlobalExceptionHandler {

    /**
     * api-governance 过滤器链的拒绝响应（如管理员校验的「无权访问」）：
     * message 即面向用户的拒绝原因，原样返回，不能落到兜底分支掩盖语义。
     */
    @ExceptionHandler(GovernanceException.class)
    public Result<?> handleGovernance(GovernanceException e) {
        log.warn("治理过滤器拒绝: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public Result<?> handleDataAccess(DataAccessException e) {
        log.error("数据访问异常: {}", e.getMessage(), e);
        return Result.error("数据操作失败，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        return Result.error("系统繁忙，请稍后重试");
    }
}

package org.example.servicejudge.Advice;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（判题服务 HTTP 面）。
 *
 * <p>约定与其它服务一致：业务异常（IllegalArgumentException / RuntimeException）
 * 消息原样回传；数据访问异常（含 SQL）与未知异常只回传通用文案，详情仅记服务端日志，
 * 避免内部实现（SQL、类名、堆栈）泄漏到前端。</p>
 *
 * <p>判题服务大量使用 IllegalStateException（判题流程状态）与 RuntimeException 表达业务失败，
 * 因此专门增加 RuntimeException 分支回传业务消息——与 AGENTS.md 约定对齐。</p>
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.example.servicejudge.controller")
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    /** 业务运行异常：IllegalStateException 等 RuntimeException 子类的消息按业务语义回传。 */
    @ExceptionHandler(RuntimeException.class)
    public Result<?> handleRuntime(RuntimeException e) {
        log.warn("业务异常: {}", e.getMessage());
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

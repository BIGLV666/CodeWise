package org.example.servicequestion.Advice;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@Slf4j
@RestControllerAdvice(basePackages = "org.example.servicequestion.controller")
public class GlobalExceptionHandler {


    @ExceptionHandler(value = Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常{}",e.getMessage(),e);
        return Result.error("系统繁忙，请稍后重试");
    }

    // 数据访问异常（SQL 语法/约束/连接等）单独兜底：原始消息含 SQL 语句，
    // 一律不回传前端，详情只进服务端日志
    @ExceptionHandler(value = DataAccessException.class)
    public Result<?> handleDataAccess(DataAccessException e) {
        log.error("数据访问异常: {}", e.getMessage(), e);
        return Result.error("数据操作失败，请稍后重试");
    }

    @ExceptionHandler(value = NullPointerException.class)
    public Result<?> handleNullPointerException(NullPointerException e) {
        log.error("空指针异常{}",e.getMessage(),e);
        return Result.error("系统繁忙"+e.getMessage());
    }

    @ExceptionHandler(value = RuntimeException.class)
    public Result<?> handleRuntimeException(RuntimeException e) {
        log.error("业务异常{}",e.getMessage(),e);
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(value = IllegalAccessError.class)
    public Result<?> handleIllegalAccessError(IllegalAccessError e) {
         log.warn("参数异常: {}", e.getMessage());
         return Result.error("系统繁忙"+e.getMessage());
    }
}

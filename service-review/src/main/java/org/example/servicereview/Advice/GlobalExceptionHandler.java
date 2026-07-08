package org.example.servicereview.Advice;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@Slf4j
@RestControllerAdvice(basePackages = "org.example.servicereview.controller")
public class GlobalExceptionHandler {


    @ExceptionHandler(value = Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常{}",e.getMessage(),e);
        return Result.error("系统异常"+e.getMessage());
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

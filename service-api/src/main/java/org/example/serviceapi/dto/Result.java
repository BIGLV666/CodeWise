package org.example.serviceapi.dto;

import lombok.Data;
import org.example.serviceapi.exception.ErrorCode;

@Data
public class Result<T> {
    private String message;
    private T data;
    private Integer code= 200;
    public static <T>Result<T> success(T data){
        Result <T> result=new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }
    public static <T>Result<T> success(T data,String message){
        Result<T> result=new Result<>();
        result.setData(data);
        result.setMessage(message);
        result.setCode(200);
        return result;
    }
    public static <T>Result<T> success(String message){
        Result<T>result=new Result<>();
        result.setCode(200);
        result.setMessage(message);
        return result;
    }
    public static <T>Result<T> error(String message,Integer code){
        Result<T>result=new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
    public static <T>Result<T>error(String message){
        Result<T>result=new Result<>();
        result.setMessage(message);
        result.setCode(400);
        return result;
    }

    /** 业务错误：HTTP 仍为 200，由 code 表达失败类型（见 CommonErrorCode 编码规则）。 */
    public static <T>Result<T> error(ErrorCode errorCode){
        Result<T>result=new Result<>();
        result.setCode(errorCode.getCode());
        result.setMessage(errorCode.getMessage());
        return result;
    }

    public static <T>Result<T> error(ErrorCode errorCode, Object... args){
        Result<T>result=new Result<>();
        result.setCode(errorCode.getCode());
        result.setMessage(String.format(errorCode.getMessage(), args));
        return result;
    }
}

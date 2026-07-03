package org.example.serviceapi.dto;

import lombok.Data;

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
}

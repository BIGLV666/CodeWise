package org.example.serviceapi.exception;

/**
 * 面向前端的错误码契约：一个常量 = 一个稳定的业务码 + 一句可直接展示的中文文案。
 */
public interface ErrorCode {

    int getCode();

    String getMessage();
}

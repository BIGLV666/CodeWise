package org.example.servicecommon.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * 基础设施层错误码（服务号 08）：用于 service-common 内部的消息信封、配置与安全边界。
 *
 * <p>这些消息只进日志、死信台账与启动失败原因，不会作为业务响应回传前端，
 * 因此文案描述「哪一类基础设施动作失败」，不写异常类名；需要原始细节时把 cause
 * 附在异常上，由日志输出。</p>
 */
public enum InfraErrorCode implements ErrorCode {

    ENVELOPE_FORMAT_INVALID(4000801, "消息格式不符合平台统一信封约定"),
    ENVELOPE_PARSE_FAILED(4000802, "消息信封解析失败"),
    MESSAGE_PAYLOAD_PARSE_FAILED(4000803, "消息载荷解析失败"),
    ENVELOPE_SERIALIZE_FAILED(5000801, "消息信封序列化失败"),
    CONFIG_MISSING(5000802, "服务必需配置缺失：%s，请检查配置注入"),
    OPERATION_NOT_ALLOWED(4030801, "该操作未开放，请使用只读检索或走人工处理流程");

    private final int code;
    private final String message;

    InfraErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

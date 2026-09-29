package org.example.serviceapi.exception;

/**
 * 跨服务通用错误码（服务号 00）。
 *
 * <p>编码规则：{@code 类别(3位) * 10000 + 服务号(2位) * 100 + 序号(2位)}。
 * 类别取 400/401/403/404/409/429/500；服务号 00 通用、01 user、02 question、
 * 03 review、04 community、05 message、06 ai、07 judge。</p>
 */
public enum CommonErrorCode implements ErrorCode {

    PARAM_INVALID(4000001, "提交的内容不符合要求，请检查后重新提交"),
    PARAM_MISSING(4000002, "缺少必要的信息，请补充后重试"),
    BODY_UNREADABLE(4000003, "请求内容格式有误，请检查后重试"),

    LOGIN_REQUIRED(4010001, "登录状态已失效，请重新登录"),
    TOKEN_INVALID(4010002, "登录凭证无效，请重新登录后再试"),

    NO_PERMISSION(4030001, "没有权限执行该操作"),
    ADMIN_ONLY(4030002, "该功能仅限管理员使用"),
    CONTENT_RESTRICTED(4030003, "该内容当前不可访问"),

    RESOURCE_NOT_FOUND(4040001, "内容不存在或已被删除"),

    STATE_CONFLICT(4090001, "当前状态不支持该操作，请刷新后重试"),
    DUPLICATE_RESOURCE(4090002, "该内容已存在，请勿重复提交"),

    RATE_LIMITED(4290001, "操作过于频繁，请稍后再试"),

    INTERNAL_ERROR(5000001, "服务繁忙，请稍后重试"),
    DATA_OPERATION_FAILED(5000002, "数据处理失败，请稍后重试"),
    DOWNSTREAM_UNAVAILABLE(5000003, "相关服务暂时无法响应，请稍后重试"),
    MESSAGE_PARSE_FAILED(5000004, "后台处理异常，请稍后重试或联系管理员");

    private final int code;
    private final String message;

    CommonErrorCode(int code, String message) {
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

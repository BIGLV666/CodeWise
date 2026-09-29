package org.example.servicemessage.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-message 错误码（服务号 05）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}。
 *
 * <p>消息文案面向用户，禁止写入 SQL、下游原始报错或异常类名；需要携带原因时用
 * {@code new BizException(枚举, cause)}，cause 只进日志。</p>
 */
public enum MessageErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(4040501, "通知不存在或已被删除"),
    LOGIN_REQUIRED_TO_VIEW(4010501, "请登录后查看通知"),
    PAGE_SIZE_INVALID(4000501, "每页数量需要在 1 到 100 之间"),

    NOTIFICATION_PAYLOAD_INCOMPLETE(4000502, "通知消息缺少必要信息，已转入后台待处理"),
    NOTIFICATION_DETAIL_INCOMPLETE(4000503, "通知消息内容不完整，已转入后台待处理"),
    NOTIFICATION_EXT_DATA_INVALID(5000501, "通知详情数据异常，请联系管理员"),

    QUESTION_INFO_UNAVAILABLE(5000502, "题目信息获取失败，稍后将自动重试"),
    REVIEW_EVENT_SERIALIZE_FAILED(5000503, "复习事件处理失败，已转入后台待处理"),
    EVENT_CLAIM_CONFLICT(5000504, "该消息正在处理中，稍后将自动重试"),
    WEBSOCKET_PUSH_FAILED(5000505, "实时推送失败，消息将重新投递"),
    MAIL_NOT_CONFIGURED(5000506, "邮件服务尚未完成配置，暂时无法发送邮件"),
    DUPLICATE_MESSAGE_HANDLER(5000507, "消息处理配置冲突，请联系管理员");

    private final int code;
    private final String message;

    MessageErrorCode(int code, String message) {
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

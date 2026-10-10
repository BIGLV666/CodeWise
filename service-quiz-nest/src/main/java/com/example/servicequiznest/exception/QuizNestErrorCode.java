package com.example.servicequiznest.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-quiz-nest 错误码（服务号 11）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}。
 *
 * <p>小组创建与加入方式各有归属，文案点明对象；未登录/无权限/参数缺失/限流/保存失败
 * 等跨服务语义一律复用 {@link org.example.serviceapi.exception.CommonErrorCode}，
 * 不在此重复定义。</p>
 *
 * <p>消息面向用户，禁止写入 SQL、下游原始报错或异常类名；需要携带原因时用
 * {@code new BizException(枚举, cause)}，cause 只进日志。</p>
 */
public enum QuizNestErrorCode implements ErrorCode {

    // ---------- 参数类 400 ----------
    GROUP_NAME_REQUIRED(4001101, "小组名称不能为空"),
    GROUP_TYPE_REQUIRED(4001102, "请选择小组加入方式"),
    GROUP_TYPE_INVALID(4001103, "小组加入方式不正确，仅支持公开、审批或邀请"),
    CREATE_TOKEN_REQUIRED(4001104, "创建凭证已失效，请重新获取后再试"),
    GROUP_ID_REQUIRED(4001105, "小组ID不能为空"),

    // ---------- 无权限 403 ----------
    NO_PERMISSION_UPDATE_GROUP(4031101, "无权限修改小组"),

    // ---------- 资源不存在 404 ----------
    NOTFOUND_USER_IN_GROUP(4041101, "您不是该小组成员"),

    // ---------- 状态冲突 409 ----------
    DUPLICATE_REQUEST(4091101, "该创建请求已提交，请勿重复提交"),

    // ---------- 服务内部 500 ----------
    CREATE_FAILED(5001101, "小组创建失败，请稍后重试"),
    UPDATE_FAILED(5001102, "小组修改失败，请稍后重试"),
    LEAVE_GROUP_FAILED(5001103, "退出小组失败，请稍后重试");

    private final int code;
    private final String message;

    QuizNestErrorCode(int code, String message) {
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

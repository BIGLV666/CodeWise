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
    NOT_GROUP_JOIN_TOKEN(4001106, "邀请码不正确或已失效，请向管理员重新获取"),
    NOT_USERID_SEND_JOIN_TOKEN(4001107, "请选择要邀请的用户"),
    CANNOT_INVITE_SELF(4001108, "不能邀请自己加入小组"),
    INVITE_CONTEXT_TOO_LONG(4001109, "邀请留言不能超过200字"),
    QUESTION_REQUIRED(4001110,"题目内容不可为空，请补充后重试"),
    QUESTION_TITLE_REQUIRED(4001111,"标题不能为空"),
    QUESTION_CONTENT_REQUIRED(4001112,"题目详情不能为空"),
    QUESTION_TITLE_TOO_LONG(4001113,"标题长度不能超过 %s 个字符"),
    QUESTION_TAGS_TOO_LONG(4001114,"标签长度不能超过 %s 个字符"),
    QUESTION_TEXT_TOO_LONG(4001115,"%s内容过长，请缩短后重试"),
    QUESTION_TIME_LIMIT_INVALID(4001116,"时间限制必须为正整数"),
    QUESTION_MEMORY_LIMIT_INVALID(4001117,"内存限制必须为正整数"),
    INVALID_GROUP_ID(4001118,"非法小组Id"),
    INVALID_QUESTION_ID(4001119,"非法题目Id"),


    // ---------- 无权限 403 ----------
    NO_PERMISSION_UPDATE_GROUP(4031101, "无权限修改小组"),
    NO_PERMISSION_SEND_JOIN_TOKEN(4031102, "您暂无权限邀请他人，请联系管理员"),
    NO_PERMISSION_ADD_QUESTION(4031103,"您暂无权限添加题目，请联系管理员"),

    // ---------- 资源不存在 404 ----------
    NOTFOUND_USER_IN_GROUP(4041101, "您不是该小组成员"),
    NOTFOUND_GROUP(4041102, "您选择的小组不存在，请确认后重试"),
    NOTFOUND_QUESTION(4041103,"您添加的题目不存在请核对后重试"),
    NOTFOUND_GROUP_QUESTION(4041104,"未找到该题目，请核对后重试"),

    // ---------- 状态冲突 409 ----------
    DUPLICATE_REQUEST(4091101, "该创建请求已提交，请勿重复提交"),
    HAVE_JOINED(4091102, "您已经加入该小组，请勿重复加入"),
    SUBMIT_APPLICATION(4091103, "您的提交申请已提交，请耐心等待"),
    GROUP_NOT_INVITE(4091104, "该小组无需邀请码，可直接加入或提交申请"),
    INVITEE_ALREADY_MEMBER(4091105, "对方已经是该小组成员，无需再次邀请"),
    QUESTION_ALREADY_IN_GROUP(4091106, "该题目已在小组题库中，请勿重复添加"),

    // ---------- 服务内部 500 ----------
    CREATE_FAILED(5001101, "小组创建失败，请稍后重试"),
    UPDATE_FAILED(5001102, "小组修改失败，请稍后重试"),
    LEAVE_GROUP_FAILED(5001103, "退出小组失败，请稍后重试"),
    JOIN_GROUP_FAILED(5001104, "加入小组失败，请稍后重试"),
    SEND_JOIN_TOKEN_FAILED(5001105, "邀请码发送失败，请稍后重试"),
    ADD_QUESTION_FAILED(5001106,"添加题目失败请稍后重试"),
    DELETE_GROUP_QUESTION_FAILED(5001107,"删除题目失败请稍后重试");

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

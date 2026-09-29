package org.example.serviceai.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-ai 错误码（服务号 06）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}，跨服务通用语义
 * （未登录、无权限、参数缺失、重复提交等）一律复用 CommonErrorCode。
 *
 * <p>消息文案面向用户，禁止写入 SQL、下游原始报错或模型厂商内部错误；需要携带
 * 原因时用 {@code new BizException(枚举, cause)}，cause 只进日志。</p>
 */
public enum AiErrorCode implements ErrorCode {

    // ===== 400：模型配置 / 任务载荷校验 =====
    CONFIG_CONTENT_REQUIRED(4000601, "模型配置内容不能为空，请填写后再提交"),
    GROUP_NAME_REQUIRED(4000602, "分组名称未填写，请先输入分组名称"),
    GROUP_NAME_TOO_LONG(4000603, "分组名称不能超过 %s 个字符"),
    API_KEY_REQUIRED(4000604, "未填写 API Key，请补充模型配置"),
    API_KEY_TOO_LONG(4000605, "填写的 API Key 过长，请确认复制了完整且正确的密钥"),
    MODEL_AT_LEAST_ONE(4000606, "请至少选择一个模型"),
    MODEL_TOO_MANY(4000607, "单个配置最多可以选择 %s 个模型"),
    MODEL_NAME_REQUIRED(4000608, "模型名称为空，请重新选择要使用的模型"),
    MODEL_NOT_IN_CONFIG(4000609, "所选模型不属于该配置，请刷新后重新选择"),
    MODEL_ARGS_INCOMPLETE(4000610, "使用自定义模型时，请同时选择配置和模型名称"),
    AI_URL_REQUIRED(4000611, "未填写模型服务地址，请先补充配置"),
    AI_URL_HTTPS_REQUIRED(4000612, "模型服务地址必须是 https 地址，请检查后重新填写"),
    AI_URL_INVALID(4000613, "模型服务地址格式不正确，请填写可直接访问的地址"),
    AI_URL_NO_QUERY(4000614, "模型服务地址不能携带额外参数，请填写基础地址"),
    AI_URL_UNRESOLVED(4000615, "模型服务地址无法解析，请检查地址填写和网络后重试"),
    AI_URL_INTERNAL_FORBIDDEN(4000616, "模型服务地址不能是本机或内部网络地址，请填写公网可访问的地址"),
    CONFIG_ID_REQUIRED(4000617, "请选择要使用的模型配置"),
    CODE_CHANGE_RANGE_INVALID(4000618, "提交的代码修改位置与当前代码不一致，请刷新后重试"),
    TESTCASE_PAYLOAD_INVALID(4000619, "测试用例生成任务信息不完整，已转入后台待处理"),
    ADVICE_PAYLOAD_INVALID(4000620, "解题提示任务信息不完整，已转入后台待处理"),
    ARTIFACT_DESC_REQUIRED(4000621, "题目描述为空，无法生成测试数据，请补充后重试"),
    FUNCTION_SIGNATURE_INCOMPLETE(4000622, "函数签名信息不完整，请补充后重试"),

    // ===== 401：调用凭证 =====
    INTERNAL_CALL_UNAUTHORIZED(4010601, "调用凭证无效，请联系管理员检查服务配置"),

    // ===== 403：配置访问与模型密钥 =====
    CONFIG_NOT_ACCESSIBLE(4030601, "模型配置不存在或无权访问"),
    PROVIDER_KEY_INVALID(4030602, "API Key 无效或权限不足，请检查模型配置"),

    // ===== 404：会话与建议消息 =====
    CONVERSATION_NOT_FOUND(4040601, "会话不存在或已被删除"),
    ADVICE_MESSAGE_NOT_FOUND(4040602, "建议消息不存在或已被删除"),

    // ===== 409：状态冲突 =====
    GROUP_NAME_DUPLICATED(4090601, "分组名称已被使用，请换一个名称"),
    SSE_STREAM_INTERRUPTED(4090602, "连接已中断，本次回答已停止生成"),

    // ===== 429：额度与频控 =====
    PROVIDER_QUOTA_EXCEEDED(4290601, "模型调用额度不足或请求过于频繁，请稍后重试"),

    // ===== 500：AI 能力链路 =====
    AI_SERVICE_NOT_READY(5000601, "AI 服务当前不可用，请稍后重试"),
    PROVIDER_CALL_FAILED(5000602, "AI 服务繁忙，请稍后重试"),
    MODEL_UNRESPONSIVE(5000603, "该模型暂时无法响应，请更换模型或稍后重试"),
    NO_AVAILABLE_MODEL(5000604, "模型服务未返回可用模型，请检查模型配置"),
    FETCH_MODELS_FAILED(5000605, "获取模型列表失败，请检查模型配置后重试"),
    MODEL_EMPTY_RESPONSE(5000606, "模型未返回有效内容，请稍后重试"),
    AI_RESULT_PARSE_FAILED(5000607, "AI 生成结果解析失败，请稍后重试"),
    AI_RESULT_INCOMPLETE(5000608, "AI 返回的内容不完整，请稍后重试"),
    GENERATED_CODE_INVALID(5000609, "生成的代码不符合运行要求，请稍后重试"),
    JUDGE_CONTEXT_FETCH_FAILED(5000610, "判题相关信息获取失败，稍后将自动重试"),
    QUESTION_INFO_FETCH_FAILED(5000611, "题目信息获取失败，稍后将自动重试"),
    CONVERSATION_CREATE_FAILED(5000612, "会话创建失败，稍后将自动重试"),
    ADVICE_RESULT_REF_INVALID(5000613, "任务进度数据无法读取，稍后将自动重试"),
    NOTIFICATION_BUILD_FAILED(5000614, "通知内容生成失败，稍后将自动重试"),
    MASTER_KEY_CONFIG_INVALID(5000615, "密钥存储服务配置不正确，请联系管理员"),
    API_KEY_ENCRYPT_FAILED(5000616, "模型密钥保存失败，请稍后重试"),
    API_KEY_DECRYPT_FAILED(5000617, "模型密钥读取失败，请重新保存模型配置"),
    SUMMARY_UPDATE_FAILED(5000618, "会话摘要更新失败，稍后将自动重试");

    private final int code;
    private final String message;

    AiErrorCode(int code, String message) {
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

    /**
     * MQ 毒消息判定：载荷缺少必要键时重试无意义，分发器据此直接死信。
     */
    public static boolean isPayloadPoison(ErrorCode errorCode) {
        return errorCode == TESTCASE_PAYLOAD_INVALID || errorCode == ADVICE_PAYLOAD_INVALID;
    }
}

package org.example.servicequestion.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-question 错误码（服务号 02）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}，
 * 即 {@code 类别(3位) * 10000 + 02 * 100 + 序号(2位)}，形如 4000201、4040201、5000201。
 *
 * <p>跨服务通用语义（未登录、无权限、仅管理员、私密内容、参数缺失、下游不可用、
 * 频率限制、重复提交等）一律复用 {@code CommonErrorCode}，此处只定义题目、
 * 测试用例、提交记录、函数题配置与判题/生成链路自有语义。</p>
 *
 * <p>消息文案面向用户，禁止写入 SQL、下游原始报错、字段英文名或异常类名；
 * 需要携带原因时用 {@code new BizException(枚举, cause)}，cause 只进日志。
 * 消息中的 {@code %s} 为占位符，由抛出点以 args 填充。</p>
 */
public enum QuestionErrorCode implements ErrorCode {

    // ---- 入参/校验（400）----
    QUESTION_ID_REQUIRED(4000201, "题目 ID 不能为空"),
    QUESTION_TITLE_REQUIRED(4000202, "标题不能为空"),
    QUESTION_CONTENT_REQUIRED(4000203, "题目详情不能为空"),
    QUESTION_LIST_REQUIRED(4000204, "题目 ID 列表不能为空"),
    QUESTION_BATCH_LIMIT_EXCEEDED(4000205, "单次最多查询 %s 道题目详情"),
    NOT_FUNCTION_QUESTION(4000206, "该题目不是函数题，不支持此操作"),
    SUBMIT_SCENE_REQUIRED(4000207, "提交类型不能为空，请刷新后重试"),
    SUBMIT_SCENE_UNSUPPORTED(4000208, "不支持该提交类型，请刷新后重试"),
    SUBMIT_RECORD_ID_REQUIRED(4000209, "提交记录 ID 不能为空"),
    SUBMIT_RECORD_LIST_REQUIRED(4000210, "提交记录 ID 列表不能为空"),
    SUBMIT_RECORD_ID_INVALID(4000211, "提交记录 ID 需为正整数"),
    SUBMIT_RECORD_BATCH_LIMIT(4000212, "单次最多查询 %s 条提交记录"),
    STANDARD_ANSWER_REQUIRED(4000213, "标准答案不能为空"),
    STANDARD_ANSWER_TOO_LONG(4000214, "标准答案长度不能超过 %s 个字符"),
    GENERATE_LANGUAGE_UNSUPPORTED(4000215, "随机测试生成暂时仅支持 Java 语言"),
    GENERATE_COUNT_INVALID(4000216, "生成数量需在 %s 到 %s 之间"),
    TASK_ID_INVALID(4000217, "任务标识有误，请核对后重试"),
    QUESTION_URL_REQUIRED(4000218, "题目地址不能为空，请填写要导入的题目"),
    QUESTION_URL_INVALID(4000219, "题目地址格式有误，请粘贴有效的题目链接"),
    FUNCTION_PARAM_CONFIG_INVALID(4000220, "函数参数配置格式有误，请检查后重试"),
    FUNCTION_PARAM_PARSE_FAILED(4000221, "无法解析函数参数声明，请检查代码模板"),
    FUNCTION_SIGNATURE_PARSE_FAILED(4000222, "无法解析函数签名，请检查题目的代码模板"),
    JAVA_TEMPLATE_MISSING(4000223, "该题目缺少 Java 代码模板，暂时无法转为函数题"),
    TESTCASE_REQUIRED(4000224, "测试用例不能为空"),
    TESTCASE_INPUT_COUNT_MISMATCH(4000225, "测试输入参数数量不匹配，期望 %s 个，实际 %s 个"),
    RANDOM_NO_PARAM_UNSUPPORTED(4000226, "无参数的函数题暂不支持随机测试生成，请手动补充测试用例"),
    RANDOM_TYPE_UNSUPPORTED(4000227, "参数类型 %s 暂不支持随机测试生成"),
    QUESTION_FILE_UNRECOGNIZED(4000228, "题目文件内容无法识别，请检查文件后重新导入"),
    QUESTION_TITLE_TOO_LONG(4000229, "标题长度不能超过 %s 个字符"),
    QUESTION_TAGS_TOO_LONG(4000230, "标签长度不能超过 %s 个字符"),
    QUESTION_TEXT_TOO_LONG(4000231, "%s内容过长，请缩短后重试"),
    QUESTION_TIME_LIMIT_INVALID(4000232, "时间限制必须为正整数"),
    QUESTION_MEMORY_LIMIT_INVALID(4000233, "内存限制必须为正整数"),

    // ---- 权限/可见性（403，账号状态语义为本服务自有）----
    ACCOUNT_UNAVAILABLE(4030201, "当前账号不可用，请联系管理员"),

    // ---- 资源不存在（404，按对象类型区分归属）----
    QUESTION_NOT_FOUND(4040201, "题目不存在或已被删除"),
    TESTCASE_NOT_FOUND(4040202, "测试用例不存在或已被删除"),
    SUBMIT_RECORD_NOT_FOUND(4040203, "提交记录不存在或已被删除"),
    JUDGE_RECORD_NOT_FOUND(4040204, "判题记录不存在或已被删除"),
    FUNCTION_CONFIG_NOT_FOUND(4040205, "该题目的函数配置不存在或已被删除"),
    GENERATION_TASK_NOT_FOUND(4040206, "生成任务不存在或已过期，请重新发起"),
    USER_NOT_FOUND(4040207, "用户信息不存在或已被删除"),
    DEBUG_RECORD_NOT_FOUND(4040208, "调试任务不存在或已过期"),

    // ---- 状态冲突/重复（409）----
    QUESTION_OFF_SHELF(4090201, "该题目已下架，暂时无法访问"),
    QUESTION_UNDER_REVIEW(4090202, "该题目正在审核中，暂时无法访问"),
    QUESTION_DUPLICATED(4090203, "该题目已存在，请勿重复导入"),
    TESTCASE_DUPLICATED(4090204, "相同输入与输出的测试用例已存在，请勿重复添加"),
    RANDOM_INPUT_INSUFFICIENT(4090205, "本次生成的测试输入重复较多，请更换随机种子后重试"),
    SUBMIT_RECORD_STATE_CONFLICT(4090206, "提交记录状态已变化，稍后将自动重试"),

    // ---- 系统内部/判题与生成链路（500，面向用户不带技术细节）----
    QUESTION_TOTAL_QUERY_FAILED(5000201, "题目总数加载失败，请稍后重试"),
    QUESTION_SAVE_FAILED(5000202, "题目保存失败，请稍后重试"),
    QUESTION_UPDATE_FAILED(5000203, "题目更新失败，请稍后重试"),
    QUESTION_DELETE_FAILED(5000204, "题目删除失败，请稍后重试"),
    TESTCASE_SAVE_FAILED(5000205, "测试用例保存失败，请稍后重试"),
    TESTCASE_UPDATE_FAILED(5000206, "测试用例更新失败，请稍后重试"),
    TESTCASE_DELETE_FAILED(5000207, "测试用例删除失败，请稍后重试"),
    FUNCTION_CONFIG_SAVE_FAILED(5000208, "函数配置保存失败，请稍后重试"),
    JUDGE_SERVICE_UNAVAILABLE(5000209, "判题服务暂时无法响应，请稍后重试"),
    ARTIFACT_DATA_INVALID(5000210, "生成的测试数据与题目要求不一致，请稍后重试"),
    GENERATE_RESULT_INCOMPLETE(5000211, "标准答案的执行结果数量不完整，请重新发起生成"),
    GENERATE_STANDARD_ANSWER_FAILED(5000212, "标准答案在随机测试输入上执行失败，请检查后重试"),
    ARTIFACT_WORKSPACE_FAILED(5000213, "生成任务准备工作目录失败，请稍后重试"),
    ARTIFACT_STATUS_READ_FAILED(5000214, "生成任务状态读取失败，请稍后重试"),
    QUESTION_IMPORT_FAILED(5000215, "题目导入失败，请稍后重试"),
    QUESTION_COUNT_UPDATE_FAILED(5000216, "题目统计更新失败，稍后将自动重试");

    private final int code;
    private final String message;

    QuestionErrorCode(int code, String message) {
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

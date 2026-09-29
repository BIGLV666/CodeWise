package org.example.servicejudge.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-judge 错误码（服务号 07）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}，
 * 形如 4000701 / 4030702 / 5000701。
 *
 * <p>跨服务通用语义（参数缺失、登录态、数据落库失败、兜底等）一律复用
 * {@link org.example.serviceapi.exception.CommonErrorCode}，本枚举只承载判题域
 * 专有语义：容器池/判题环境、判题任务与重试、函数题目配置等。</p>
 *
 * <p>消息文案面向用户（或容器池管理界面的管理员），禁止写入 SQL、docker 原始
 * 报错、命令输出或异常类名；需要携带原因时用 {@code new BizException(枚举, cause)}，
 * cause 只进服务端日志。</p>
 */
public enum JudgeErrorCode implements ErrorCode {

    // ========== 400 参数类 ==========

    /** 容器池扩容/缩容时传入的语言不在 java/python/cpp/c 之内。 */
    LANGUAGE_UNSUPPORTED(4000701, "该语言暂不支持判题，请选择 Java、Python、C 或 C++"),

    /** 函数题目以非 Java 语言提交或调试。 */
    FUNCTION_MODE_JAVA_ONLY(4000702, "函数题目当前仅支持 Java 语言"),

    /** 删除容器时未指定要操作的容器标识。 */
    CONTAINER_ID_MISSING(4000703, "缺少要操作的判题环境标识，请刷新后重新选择"),

    /** 失败记录列表查询传入的 status 不在可选取值之内。 */
    FAILURE_STATUS_INVALID(4000704, "任务状态取值不在可选范围内，请刷新后重试"),

    /** 函数题目参数配置中的类型无法生成调用代码。 */
    FUNCTION_PARAM_TYPE_UNSUPPORTED(4000705, "函数题目的参数类型暂不支持，请联系管理员"),

    /** 题目类型既非 ACM 也非函数模式。 */
    QUESTION_TYPE_UNSUPPORTED(4000706, "题目类型暂不支持判题，请联系管理员"),

    // ========== 404 资源类 ==========

    /** 判题任务（提交记录/失败记录）不存在或已被清理。 */
    JUDGE_TASK_NOT_FOUND(4040701, "判题任务不存在或已被清理"),

    /** 判题所需题目信息缺失或已被删除。 */
    JUDGE_QUESTION_NOT_FOUND(4040702, "题目信息不存在或已被清理"),

    /** 容器池管理界面中指定的容器已不在池内。 */
    CONTAINER_NOT_FOUND(4040703, "该判题环境不存在或已被回收，请刷新后重试"),

    /** 函数题目缺少方法签名配置。 */
    FUNCTION_CONFIG_NOT_FOUND(4040704, "函数题目配置不存在，请联系管理员"),

    // ========== 409 状态冲突/重复类 ==========

    /** 提交或重试时任务状态不允许发起（非 pending 等）。 */
    JUDGE_RETRY_NOT_ALLOWED(4090701, "当前状态不支持该操作，请刷新后重试"),

    /** 对已发起过或已处理完成的失败任务再次发起重试。 */
    DUPLICATE_RETRY_REQUEST(4090702, "该任务已发起过重试或已处理完成，请勿重复操作"),

    /** 删除正在被判题任务占用的容器。 */
    CONTAINER_IN_USE(4090703, "该判题环境正在执行任务，请等待其空闲后再删除"),

    /** 每种语言至少保留一个判题环境，拒绝删到空。 */
    CONTAINER_LAST_ONE(4090704, "每种语言的判题环境至少保留一个，无法删除"),

    // ========== 500 判题能力/环境类 ==========

    /** 判题能力不可用（借还容器超时、池耗尽、判题器返回空结果等）。 */
    JUDGE_ENV_UNAVAILABLE(5000701, "判题服务暂时无法响应，请稍后重试"),

    /** 容器池内部队列异常导致扩容未完成。 */
    CONTAINER_POOL_UNAVAILABLE(5000702, "判题环境资源紧张，本次扩容未完成，请稍后重试"),

    /** 判题环境（容器）创建失败，docker 原始错误只进日志。 */
    CONTAINER_CREATE_FAILED(5000703, "判题环境创建失败，请稍后重试"),

    /** 语言镜像未预装（docker-java 报 NotFound），需管理员补齐镜像。 */
    SANDBOX_IMAGE_MISSING(5000704, "判题沙箱镜像缺失，请联系管理员确认运行环境已就绪"),

    /** 判题环境物理删除失败，docker 原始错误只进日志。 */
    CONTAINER_REMOVE_FAILED(5000705, "判题环境回收失败，请稍后重试"),

    /** 函数模式生成的调用入口缺失（内部编排错误，非用户可修复）。 */
    FUNCTION_MAIN_MISSING(5000706, "函数题目的运行入口生成失败，请联系管理员"),

    /** 编译或执行环境失败（工作区打包/传输等执行链路故障），细节只进日志。 */
    JUDGE_EXECUTION_FAILED(5000707, "判题执行环境出现问题，请稍后重试"),

    /** 重试消息处理未完成（兜底包装非运行时异常），原始原因只进日志。 */
    JUDGE_RETRY_FAILED(5000708, "判题重试未能完成，请稍后刷新查看结果");

    private final int code;
    private final String message;

    JudgeErrorCode(int code, String message) {
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

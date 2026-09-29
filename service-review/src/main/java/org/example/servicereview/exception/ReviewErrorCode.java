package org.example.servicereview.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-review 错误码（服务号 03）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}，
 * 即 {@code 类别(3位) * 10000 + 03 * 100 + 序号(2位)}，形如 4040301。
 *
 * <p>跨服务通用语义（未登录、无权限、参数缺失、下游不可用、数据保存失败、
 * 兜底内部错误等）一律复用 {@link org.example.serviceapi.exception.CommonErrorCode}，
 * 本枚举只收录收藏夹 / 笔记与文件夹 / 进度计划 / 复习四类业务特有语义。</p>
 *
 * <p>消息文案面向用户，禁止写入 SQL、下游原始报错、英文字段名或异常类名；
 * 需要携带原因时用 {@code new BizException(枚举, cause)}，cause 只进日志。
 * 消息中的 {@code %s} 为占位符，由调用点传参（如动态长度上限、对象名称）。</p>
 */
public enum ReviewErrorCode implements ErrorCode {

    // ==================== 收藏夹 ====================
    FAVORITE_NOT_FOUND(4040301, "收藏夹不存在或已被删除"),
    QUESTION_NOT_IN_FAVORITE(4040302, "该题目不在此收藏夹中"),
    QUESTION_ALREADY_IN_FAVORITE(4090301, "该题目已在此收藏夹中，请勿重复添加"),
    FAVORITE_MOVE_TARGET_MISSING(4000301, "请选择要移动的源收藏夹和目标收藏夹"),
    FAVORITE_MOVE_SAME_FOLDER(4000302, "源收藏夹与目标收藏夹不能相同"),
    QUESTION_LIST_EMPTY(4000303, "请至少选择一道题目"),
    QUESTION_BATCH_LIMIT(4000304, "单次最多操作 %s 道题目"),
    QUESTION_ID_INVALID(4000305, "题目编号不符合要求，请检查后重试"),

    // ==================== 笔记与文件夹 ====================
    NOTE_NOT_FOUND(4040303, "笔记不存在或已被删除"),
    NOTE_FOLDER_NOT_FOUND(4040304, "文件夹不存在或已被删除"),
    NOTE_CONTENT_EMPTY(4000306, "笔记内容不能为空，请先填写内容"),
    NOTE_CONTENT_TOO_LONG(4000307, "笔记内容过长，最多 %s 个字符"),
    NOTE_CONTENT_TYPE_UNSUPPORTED(4000308, "暂不支持该笔记内容类型，请刷新后重新选择"),
    NOTE_FOLDER_ID_INVALID(4000309, "文件夹选择不正确，请刷新后重新选择"),
    NO_UPDATE_FIELDS(4000310, "没有需要更新的内容，请修改后重试"),
    FIELD_REQUIRED(4000311, "%s不能为空"),
    FIELD_TOO_LONG(4000312, "%s长度不能超过 %s 个字符"),
    PAGE_SIZE_INVALID(4000313, "每页数量需要大于 0"),

    // ==================== 幂等防重 ====================
    RESOURCE_ALREADY_CREATED(4090302, "该%s已创建，请勿重复提交"),

    // ==================== 进度计划 ====================
    PLAN_NOT_FOUND(4040305, "进度计划不存在或已被删除"),
    PLAN_DUPLICATE(4090303, "该题目已存在进度计划，请勿重复创建"),
    PLAN_CREATE_FAILED(5000301, "进度计划保存失败，请稍后重试"),
    PLAN_QUESTION_MISSING(4000314, "请选择要加入计划的题目"),
    PLAN_BEGIN_TIME_MISSING(4000315, "请选择计划的开始日期"),
    PLAN_BEGIN_TIME_PAST(4000316, "开始日期不能早于今天，请选择今天或之后的日期"),
    /** 计划状态机不可逆：未开始→进行中→已过期/已完成，过期后只能删除重建，不能再改开始日期。 */
    PLAN_TIME_NOT_EDITABLE(4090304, "该计划已开始或已过期，无法再修改开始日期，请刷新后重试"),
    PLAN_LIST_EMPTY(4000317, "请至少提交一条计划"),
    PLAN_BATCH_LIMIT(4000318, "单次最多提交 %s 条计划"),
    PLAN_ID_LIST_EMPTY(4000319, "请至少选择一条计划"),
    PLAN_DELETE_BATCH_LIMIT(4000320, "单次最多删除 %s 条计划"),
    PLAN_UPDATE_BUSY(4090305, "该计划正在更新，请稍后重试"),
    CALENDAR_RANGE_INVALID(4000321, "日历统计范围仅支持按天、按周或按月"),

    // ==================== 复习 ====================
    REVIEW_ALREADY_PLANNED(4090306, "该题目已在您的复习计划中，请勿重复添加"),
    REVIEW_ITEM_NOT_FOUND(4040306, "该题目的复习计划不存在或已被删除"),
    REVIEW_RECORD_NOT_FOUND(4040307, "复习记录不存在或已被删除"),
    REVIEW_DAY_NOT_FOUND(4040308, "该日复习计划不存在或已被删除"),
    REVIEW_TODAY_LOCKED(4090307, "该题目已列入今日复习，暂不支持修改"),
    REVIEW_ALREADY_MASTERED(4090308, "该题目已掌握，无需再修改"),
    REVIEW_STATUS_INVALID(4000322, "复习状态选择不正确，仅支持暂停或恢复"),
    REVIEW_WEIGHT_INVALID(4000323, "复习权重设置不正确，仅支持 0 或 1"),
    REVIEW_QUALITY_INVALID(4000324, "复习质量评分需要在 0 到 5 之间"),
    REVIEW_DATE_MISSING(4000325, "请选择要查看的日期"),
    REVIEW_COUNT_INVALID(4000326, "每日复习数量不能小于 0"),
    REVIEW_SWITCH_INVALID(4000327, "开关类配置仅支持 0 或 1，请重新设置"),
    REVIEW_MIN_EF_INVALID(4000328, "最低难度因子不能小于 1.3"),
    REVIEW_EF_ORDER_INVALID(4000329, "初始难度因子不能小于最低难度因子"),
    REVIEW_MASTERED_DAYS_INVALID(4000330, "掌握间隔天数必须大于 0"),
    REVIEW_UPDATE_BUSY(4090309, "复习记录正在更新，请稍后重试"),

    // ==================== 消息消费（内部） ====================
    EVENT_CLAIM_CONFLICT(5000302, "后台任务登记冲突，稍后将自动重试");

    private final int code;
    private final String message;

    ReviewErrorCode(int code, String message) {
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

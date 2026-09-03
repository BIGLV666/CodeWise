package org.example.serviceapi.dto.event;

/**
 * 统一消息信封的事件类型常量。
 *
 * <p>类型命名遵循「领域_对象_动作」大写下划线风格；
 * 新增事件类型时先在此登记，再在生产/消费两侧引用，避免散落魔法值。</p>
 */
public final class EventTypes {

    private EventTypes() {
    }

    /** 用户提交判题请求：question -> judge，payload 为 submitRecordId */
    public static final String JUDGE_SUBMIT_REQUEST = "JUDGE_SUBMIT_REQUEST";

    /** 调试判题请求：question -> judge，payload 为 debug 任务 uuid */
    public static final String JUDGE_DEBUG_REQUEST = "JUDGE_DEBUG_REQUEST";

    /** 失败提交人工/补偿重试：judge 内部，payload 为 failureSubmitId */
    public static final String JUDGE_RETRY_REQUEST = "JUDGE_RETRY_REQUEST";

    /** 判题结果回调：judge -> question，payload 为 judgeRecordId */
    public static final String JUDGE_RESULT_CALLBACK = "JUDGE_RESULT_CALLBACK";

    /** AI 判题建议请求：judge -> ai，payload 为瘦身后的事件引用 DTO */
    public static final String AI_ADVICE_REQUEST = "AI_ADVICE_REQUEST";

    /** AI 函数题用例生成请求：question -> ai，payload 为 QuestionMessage（原为事务内裸发，已收编 Outbox） */
    public static final String AI_TESTCASE_REQUEST = "AI_TESTCASE_REQUEST";

    /** 申诉处理结果通知：community -> message，payload 为 NotificationDto（原为事务内裸发，已收编 Outbox） */
    public static final String NOTIFICATION_APPEAL = "NOTIFICATION_APPEAL";

    /** 复习场景判题结果：question -> review，payload 为 ReviewJudgeRecordDto */
    public static final String REVIEW_JUDGE_RECORD = "REVIEW_JUDGE_RECORD";

    /** 复习提醒通知：review -> message，payload 为 NotificationDto（含每日幂等 messageId） */
    public static final String REVIEW_REMINDER = "REVIEW_REMINDER";

    /** 复习掌握祝贺：review -> message，payload 为 ReviewMasteredDto（题目名由消费端 Feign 补齐） */
    public static final String REVIEW_MASTERED = "REVIEW_MASTERED";
    /** 计划场景判题结果：question -> plan，payload 为 PlanJudgeRecordDto */
    public static final String PLAN_JUDGE_RECORD = "PLAN_JUDGE_RECORD";
}

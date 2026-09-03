package org.example.servicecommon.config;

public  class MqContexts {
    // 邮件队列
    public static final String MESSAGE_QUEUE_NAME = "message.queue";
    public static final String MESSAGE_EXCHANGE = "message.exchange";
    public static final String MESSAGE_ROUTING_KEY = "email.routing";
    public static final String WEBSOCKET_ROUTING_KEY = "websocket.routing";

    //AI队列
    /** @deprecated 旧 AI 队列，broker 参数不可变无法补挂 DLX，已弃用；发布时需排空（见 docs/maintenance/messaging-reliability.md） */
    @Deprecated
    public static final String Ai_QUEUE_NAME = "ai.queue";
    public static final String Ai_EXCHANGE = "ai.exchange";
    public static final String Ai_ROUTING_KEY = "ai.routing";
    public static final String Ai_TESTCASE_ROUTING_KEY = "ai.testcase.routing";
    //ai建议队列
    public static final String AI_WA_ADVICE_ROUTING_KEY = "ai.wa-advice.routing";

    // ========== AI 队列 v2：testcase/advice 拆分独立队列并统一挂 ai.dlx ==========
    // 旧 ai.queue 因 broker 端参数不可变无法补挂 DLX，已弃用（不再声明）。
    /** AI 测试用例生成队列（挂 ai.dlx，消费失败超限后死信） */
    public static final String AI_TESTCASE_QUEUE = "ai.testcase.queue";
    /** AI 判题失败建议队列（挂 ai.dlx） */
    public static final String AI_ADVICE_QUEUE = "ai.advice.queue";
    /** 延迟重试等待队列（无消费者）：testcase 消息 TTL 到期后经 DLX 弹回 ai.testcase.queue */
    public static final String AI_TESTCASE_WAIT_QUEUE = "ai.testcase.wait.queue";
    /** 延迟重试等待队列（无消费者）：advice 消息 TTL 到期后经 DLX 弹回 ai.advice.queue */
    public static final String AI_ADVICE_WAIT_QUEUE = "ai.advice.wait.queue";
    /** AI 死信交换机 */
    public static final String AI_DLX = "ai.dlx";
    /** AI 死信队列 */
    public static final String AI_DLQ = "ai.dead.queue";
    /** AI 死信路由键 */
    public static final String AI_DEAD_ROUTING_KEY = "ai.dead";
    //题目队列
    public static final String Question_QUEUE_NAME = "question.queue";
    public static final String Question_EXCHANGE = "question.exchange";
    public static final String Question_TESTCASE_ROUTING_KEY = "question.testcace.routing";
    public static final String QUESTION_DELETE_QUESTION_ROUTING_KEY = "question.delete.queue.routing";
    public static final String QUESTION_SUBMIT_RECORD_ROUTING_KEY = "question.submit.record.routing";
    public static final String QUESTION_DEBUG_ROUTING_KEY = "question.debug.routing";
    //判题队列
    public static final String JUDGE_QUEUE_NAME = "judge.queue";
    public static final String JUDGE_EXCHANGE = "judge.exchange";
    public static final String JUDGE_ROUTING_KEY = "judge.routing";
    public static final String JUDGE_DEBUG_ROUTING_KEY = "judge.debug.routing";
    //判题死信队列
    public static final String JUDGE_DLX = "judge.dlx";
    public static final String JUDGE_DLQ = "judge.dead.queue";
    //判题死信路由键
    public static final String JUDGE_DEAD_ROUTING_KEY = "judge.dead";
    //判题重试队列
    public static final String JUDGE_RETRY_ROUTING_KEY="judge.retry.routing";

    // ========== 判题队列 v2：submit/debug/retry 拆分为独立队列并挂 DLX ==========
    // 旧 judge.queue 在 broker 中参数不可变（无法补挂 DLX），已弃用；
    // 发布时需排空旧队列（详见 docs/maintenance/messaging-reliability.md）。
    /** 判题提交队列（挂 judge.dlx，消费失败超限后死信） */
    public static final String JUDGE_SUBMIT_QUEUE = "judge.submit.queue";
    /** 调试判题队列（挂 judge.dlx） */
    public static final String JUDGE_DEBUG_QUEUE = "judge.debug.queue";
    /** 人工/补偿重试队列（挂 judge.dlx，消息体为 failureSubmitId） */
    public static final String JUDGE_RETRY_QUEUE = "judge.retry.queue";
    /** 延迟重试等待队列：无消费者，TTL 到期后经 DLX 弹回 judge.submit.queue */
    public static final String JUDGE_WAIT_QUEUE = "judge.wait.queue";

    //复习队列
    public static final String REVIEW_QUEUE_NAME = "reviews.queue";
    public static final String REVIEW_EXCHANGE = "reviews.exchange";
    public static final String REVIEW_JUDGE_RECORD_ROUTING_KEY = "reviews.judge.record.routing";
    public static final String PLAN_JUDGE_RECORD_ROUTING_KEY = "plan.judge.record.routing";
    /**
     * 进度计划判题结果独立队列：与 reviews.queue 消费同一交换机但路由键不同。
     * 必须与复习消费分开——同一队列挂两个 @RabbitListener 会形成竞争消费者，
     * 各自把不匹配路由键的消息 ACK 丢弃，导致两类消息各丢约一半。
     */
    public static final String REVIEW_PLAN_QUEUE_NAME = "reviews.plan.queue";
    //用户队列
    public static final String USER_QUEUE_NAME = "user.queue";
    public static final String USER_EXCHANGE = "user.exchange";
    public static final String USER_JUDGE_ROUTING_KEY = "user.judge.routing";
    //收件箱队列
    public static final String NOTIFICATION_QUEUE_NAME = "notification.queue";
    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_LIKE_ROUTING_KEY = "notification.like.routing";
    public static final String NOTIFICATION_REVIEW_ROUTING_KEY = "notification.review.routing";
    public static final String NOTIFICATION_AI_ADVICE_ROUTING_KEY = "notification.ai.advice.routing";
    public static final String NOTIFICATION_CHECKED_ROUTING_KEY = "notification.checked.routing";
    public static final String NOTIFICATION_APPEAL_ROUTING_KEY = "notification.appeal.routing";
    /** 复习掌握祝贺通知：review -> message（经 Outbox），payload 为 ReviewMasteredDto */
    public static final String NOTIFICATION_REVIEW_MASTERED_ROUTING_KEY = "notification.review.mastered.routing";

}

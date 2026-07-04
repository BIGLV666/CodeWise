package org.example.servicecommon.config;

public  class MqContexts {
    // 邮件队列
    public static final String EMAIL_QUEUE = "email.queue";
    public static final String EMAIL_EXCHANGE = "email.exchange";
    public static final String EMAIL_ROUTING_KEY = "email.routing";

    //AI队列
    public static final String Ai_QUEUE_NAME = "ai.queue";
    public static final String Ai_EXCHANGE = "ai.exchange";
    public static final String Ai_ROUTING_KEY = "ai.routing";
    public static final String Ai_TESTCASE_ROUTING_KEY = "ai.testcase.routing";
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
}

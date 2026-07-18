package org.example.servicecommon.RedisDto;

public  class RedisContext {
    //======判题键-=======================
    public static final String JUDGE_SUCCESS_KEY = "judge_debug_success:";
    public static final String QUESTION_SUCCESS_KEY = "question_debug_success:";
    public static final String JUDGE_DEBUG_KEY = "judge_debug_key";
    public static final String JUDGE_RESULT_KEY = "judge_result_key";
    //幂等键
    public static final String  REQUEST_ID_KEY = "request_id_key";
    //======热帖缓存-=======================
    public static final String POST_ID_KEY = "post_id_key";
    public static final String POST_VO_KEY = "post_vo_key";
    //============题目缓存键=================
    public static final String  QUESTION_ID_KEY = "question_id_key";
    public static final String  QUESTION_TOTAL_KEY = "question_total_key";
    //===========点赞=========================
    public static final String LIKE_POST_BUCKET_KEY ="like_bucket_key";
    public static final String LIKE_COMMENT_BUCKET_KEY ="like_comment_bucket_key";
    public static final String LIKE_SOLUTION_BUCKET_KEY ="like_solution_bucket_key";
    public static final String LIKE_POST_KEY = "like_post_key";
    public static final String LIKE_COMMENT_KEY = "like_comment_key";
    public static final String LIKE_SOLUTION_KEY = "like_solution_key";
    //===========题解浏览量=====================
    public static final String SOLUTION_LOOK_BUCKET_KEY = "solution_look_bucket_key";
    public static final String SOLUTION_LOOK_KEY = "solution_look_key";
    //===============异步删除评论和点赞键====================
    public static final String DELETE_COMMENT_KEY = "delete_comment_key";
    public static final String DELETE_LIKE_RECORD_KEY = "delete_like_record_key";
    //=================排行================================
    public static final String HOST_POST_KEY = "host_post_key";
    //=============mq多次消费失败=========================
    public static final String MQ_LIKE_FAILED_KEY = "mq_like_failed_key";
    public static final String MQ_LIKE_RETRY_COUNT_KEY = "mq_like_retry_count_key";
    public static final String MQ_REVIEW_FAILED_KEY = "mq_review_failed_key";
    public static final String MQ_REVIEW_RETRY_COUNT_KEY = "mq_review_retry_count_key";
    public static final String NOTIFICATION_IDEMPOTENT_KEY = "notification:idempotent:";
    public static final String AI_ADVICE_CONSUME_IDEMPOTENT_KEY = "ai:advice:consume:";
    public static final String AI_ADVICE_PUSH_IDEMPOTENT_KEY = "ai:advice:push:";
    public static final String REVIEW_JUDGE_RETRY_COUNT_KEY = "review:judge:retry-count";
    public static final String REVIEW_JUDGE_FAILED_KEY = "review:judge:failed";
    //===============点赞作者id缓存=========================
    public static final String POST_AND_USER_ID_KEY = "post_and_user_id_key:v2";
    public static final String COMMENT_AND_USER_ID = "comment_and_user_id_key:v2";
    public static final String SOLUTION_AND_USER_ID = "solution_and_user_id_key:v2";
}

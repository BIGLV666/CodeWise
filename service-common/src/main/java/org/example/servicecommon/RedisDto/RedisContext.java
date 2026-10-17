package org.example.servicecommon.RedisDto;

public  class RedisContext {
    //======判题键-=======================
    public static final String JUDGE_SUCCESS_KEY = "judge_debug_success:";
    public static final String QUESTION_SUCCESS_KEY = "question_debug_success:";
    public static final String JUDGE_DEBUG_KEY = "judge_debug_key";
    public static final String JUDGE_RESULT_KEY = "judge_result_key";
    public static final String FUNCTION_CASE_GENERATION_TASK_KEY = "function_case_generation_task:";
    //幂等键
    public static final String  REQUEST_ID_KEY = "request_id_key";
    //======热帖缓存-=======================
    public static final String POST_ID_KEY = "post_id_key";
    public static final String POST_KEY = "post_key";
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
    public static final String MQ_CHECKED_FAILED_KEY = "mq_checked_failed_key";
    public static final String MQ_CHECKED_RETRY_COUNT_KEY = "mq_checked_retry_count_key";
    public static final String MQ_APPEAL_FAILED_KEY = "mq_appeal_failed_key";
    public static final String MQ_APPEAL_RETRY_COUNT_KEY = "mq_appeal_retry_count_key";
    public static final String NOTIFICATION_IDEMPOTENT_KEY = "notification:idempotent:";
    public static final String AI_ADVICE_CONSUME_IDEMPOTENT_KEY = "ai:advice:consume:";
    public static final String AI_ADVICE_PUSH_IDEMPOTENT_KEY = "ai:advice:push:";
    public static final String REVIEW_JUDGE_RETRY_COUNT_KEY = "review:judge:retry-count";
    public static final String REVIEW_JUDGE_FAILED_KEY = "review:judge:failed";
    //===============点赞作者id缓存=========================
    public static final String POST_AND_USER_ID_KEY = "post_and_user_id_key:v2";
    public static final String COMMENT_AND_USER_ID = "comment_and_user_id_key:v2";
    public static final String SOLUTION_AND_USER_ID = "solution_and_user_id_key:v2";

    //===============已登出的token=========================
    public static final String LOGOUT_TOKEN="logout_token";
    //===============用户级凭证失效线========================
    /**
     * 用户级凭证失效线 key 前缀，value 为 <b>epoch 秒</b>（纯数字字符串，供 Lua {@code tonumber} 解析）。
     *
     * <p>语义：「该用户在此时间点<b>之前</b>签发的 token 全部失效」。管理员封禁、改密、
     * 全端下线都写它；拦截器用网关注入的 JWT {@code iat} 与之比较。</p>
     *
     * <p>解封时<b>不清除</b>：清掉会让封禁期间未使用过的旧 token 复活。新登录签发的 token
     * {@code iat} 更晚，自然通过判定，所以不需要任何「解封清理」动作。</p>
     */
    public static final String USER_TOKEN_REVOKE_BEFORE = "revoke_before";

    /**
     * 凭证失效兜底 TTL（秒）。
     *
     * <p>登出黑名单、失效线都靠它自然过期，因此必须<b>大于等于</b>
     * {@code jwt.expiration}（当前默认 24h），否则失效线会先于凭证过期，
     * 出现「token 还没过期但已不在失效范围内」的窗口。</p>
     */
    public static final long SESSION_REVOKE_TTL_SECONDS = 7 * 24 * 3600L;

    /**
     * token 桶 key：存在即该凭证已失效（登出、以及被失效线判定拒绝后的兜底落库）。
     */
    public static String logoutTokenKey(String token) {
        return LOGOUT_TOKEN + ":" + token;
    }

    /**
     * 用户级失效线 key：value 为该用户「凭证失效时间点」的 epoch 秒。
     */
    public static String revokeBeforeKey(Long userId) {
        return USER_TOKEN_REVOKE_BEFORE + ":" + userId;
    }
}

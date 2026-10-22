package org.example.servicecommon.RedisDto;

public  class RedisContext {
    //======判题键-=======================
    public static final String JUDGE_SUCCESS_KEY = "judge_debug_success:";
    public static final String QUESTION_SUCCESS_KEY = "question_debug_success:";
    public static final String JUDGE_DEBUG_KEY = "judge_debug_key";
    public static final String JUDGE_RESULT_KEY = "judge_result_key";
    public static final String FUNCTION_CASE_GENERATION_TASK_KEY = "function_case_generation_task:";    //幂等键
    public static final String FUNCTION_BATCH_IMPORT_TASK_KEY = "function_batch_import_task:";
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

    //===============登录失败风控=========================
    /**
     * 登录失败滑动窗口（ZSET）：score 为 Redis TIME 毫秒，member 为单次请求的唯一标识。
     *
     * <p>窗口只在登录失败时写入，成功登录会整体删除；无新失败后由 PEXPIRE 自然过期。</p>
     */
    public static final String LOGIN_FAIL_WINDOW_KEY = "login:fail:window";

    /**
     * 风控次数（STRING，纯数字）：每次滑动窗口达到阈值时 INCR，成功登录时删除。
     *
     * <p>TTL 为 24 小时滚动续期，避免长期不登录的账号把档位永久累积到最高档。</p>
     */
    public static final String LOGIN_RISK_COUNT_KEY = "login:risk:count";

    /**
     * 登录冻结（STRING，冻结到期 epoch 秒）：TTL 即本次冻结剩余时长。
     *
     * <p>与 {@link #revokeBeforeKey(Long)} 分离：失效线负责让旧 token 失效（TTL 必须覆盖
     * token 生命期），冻结 key 负责表达「本次风控持续多久」，两者不能共用一个 TTL。</p>
     */
    public static final String LOGIN_FREEZE_KEY = "login:freeze";

    /** 登录失败滑动窗口 key。 */
    public static String loginFailWindowKey(Long userId) {
        return LOGIN_FAIL_WINDOW_KEY + ":" + userId;
    }

    /** 登录风控次数 key。 */
    public static String loginRiskCountKey(Long userId) {
        return LOGIN_RISK_COUNT_KEY + ":" + userId;
    }

    /** 登录冻结 key。 */
    public static String loginFreezeKey(Long userId) {
        return LOGIN_FREEZE_KEY + ":" + userId;
    }

    /**
     * 登录账号元数据缓存 key（STRING）：value 为 {@code userId|status}，账号不存在时缓存 {@code 0}。
     *
     * <p>登录失败风控每次都要把账号换成 userId；缓存后失败链路不再回库，
     * 只有缓存未命中（或 Redis 故障降级）时才查一次数据库。</p>
     */
    public static final String LOGIN_USER_META_KEY = "login:user:meta";

    /** 登录风控邮件节流 key（STRING）：SET NX EX，避免攻击者反复触发把用户邮箱打爆。 */
    public static final String LOGIN_RISK_MAIL_KEY = "login:risk:mail";

    /** 登录账号元数据缓存 key。 */
    public static String loginUserMetaKey(String account) {
        return LOGIN_USER_META_KEY + ":" + account;
    }

    /** 登录风控邮件节流 key。 */
    public static String loginRiskMailKey(Long userId) {
        return LOGIN_RISK_MAIL_KEY + ":" + userId;
    }
}

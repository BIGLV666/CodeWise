package org.example.servicecommon.RedisDto;

public  class RedisContext {
    //======判题键-=======================
    public static final String JUDGE_SUCCESS_KEY = "judge_debug_success:";
    public static final String QUESTION_SUCCESS_KEY = "question_debug_success:";
    public static final String JUDGE_DEBUG_KEY = "judge_debug_key";
    public static final String JUDGE_RESULT_KEY = "judge_result_key";
    //幂等键
    public static final String  REQUEST_ID_KEY = "request_id_key";
    //======收藏键-=======================
    //============题目缓存键=================
    public static final String  QUESTION_ID_KEY = "question_id_key";
    public static final String  QUESTION_TOTAL_KEY = "question_total_key";
    //===========点赞=========================
    public static final String LIKE_POST_BUCKET_KEY ="like_bucket_key";
    public static final String LIKE_COMMENT_BUCKET_KEY ="like_comment_bucket_key";
    public static final String LIKE_POST_KEY = "like_post_key";
    public static final String LIKE_COMMENT_KEY = "like_comment_key";
    
}

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
    
}

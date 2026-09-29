package org.example.servicecommunity.exception;

import org.example.serviceapi.exception.ErrorCode;

/**
 * service-community 错误码（服务号 04）：编码规则见
 * {@link org.example.serviceapi.exception.CommonErrorCode}。
 *
 * <p>帖子、题解、评论、点赞、审核、申诉各有归属，文案点明对象类型；
 * 未登录/无权限/参数/限流/保存失败等跨服务语义一律复用
 * {@link org.example.serviceapi.exception.CommonErrorCode}，不在此重复定义。</p>
 *
 * <p>消息面向用户，禁止写入 SQL、下游原始报错或异常类名；需要携带原因时用
 * {@code new BizException(枚举, cause)}，cause 只进日志。</p>
 */
public enum CommunityErrorCode implements ErrorCode {

    // ---------- 参数类 400 ----------
    PAGE_SIZE_INVALID(4000401, "每页数量需要在 1 到 100 之间"),
    POST_TITLE_CONTENT_EMPTY(4000402, "帖子标题和内容不能为空"),
    POST_TITLE_TOO_LONG(4000403, "帖子标题最多 %s 个字符"),
    TAG_TOO_LONG(4000404, "单个标签长度需少于 %s 个字符"),
    SOLUTION_FIELDS_REQUIRED(4000405, "题目、题解标题和题解正文不能为空"),
    SOLUTION_TITLE_TOO_LONG(4000406, "题解标题最多 %s 个字符"),
    COMMENT_CONTENT_REQUIRED(4000407, "评论内容与所评内容不能为空"),
    SEARCH_VALUE_EMPTY(4000408, "搜索内容不能为空，请输入关键词或完整标签"),
    SEARCH_VALUE_TOO_LONG(4000409, "搜索内容最多 %s 个字符"),
    POST_CONTENT_TOO_LONG(4000410, "帖子正文最多 %s 个字符"),
    COMMENT_TEXT_TOO_LONG(4000411, "评论内容最多 %s 个字符"),
    CONTENT_TYPE_INVALID(4000412, "内容类型不正确，请选择帖子、题解或评论"),
    SEARCH_FILTER_CONFLICT(4000413, "关键词与标签只需提供其中一个，且不可同时提供"),
    POST_ORDER_INVALID(4000414, "排序方式仅支持最新或最早"),
    LIKE_ACTION_INVALID(4000415, "点赞操作仅支持点赞或取消点赞"),
    LIKE_TARGET_TYPE_UNSUPPORTED(4000416, "仅支持对帖子或评论点赞"),
    CHECK_STATUS_INVALID(4000417, "审核结论仅支持通过或不通过"),

    // ---------- 资源不存在 404 ----------
    POST_NOT_FOUND(4040401, "帖子不存在或已被删除"),
    COMMENT_NOT_FOUND(4040402, "评论不存在或已被删除"),
    SOLUTION_NOT_FOUND(4040403, "题解不存在或已被删除"),
    APPEAL_NOT_FOUND(4040404, "申诉记录不存在或已被删除"),
    APPEAL_TARGET_NOT_FOUND(4040405, "申诉关联的内容不存在或已被删除"),
    QUESTION_NOT_FOUND(4040406, "题目不存在或已被删除"),
    COMMENT_SOLUTION_NOT_FOUND(4040407, "评论所属的题解不存在或已被删除"),
    LIKE_USER_INFO_MISSING(4040408, "点赞用户信息不存在，暂时无法生成通知"),

    // ---------- 状态冲突 409 ----------
    APPEAL_ALREADY_PENDING(4090401, "该申诉正在审核中，请耐心等待，勿重复提交"),
    APPEAL_ALREADY_HANDLED(4090402, "该申诉已处理，无需重复操作"),
    APPEAL_NOT_APPEALABLE(4090403, "该内容当前状态无需申诉"),

    // ---------- 服务内部 500 ----------
    VIEW_COUNT_UNAVAILABLE(5000401, "浏览量记录暂时不可用，请稍后重试"),
    OPERATION_INTERRUPTED(5000402, "操作未能完成，请稍后重试"),
    RANKING_UNAVAILABLE(5000403, "热门榜单暂时无法加载，请稍后重试");

    private final int code;
    private final String message;

    CommunityErrorCode(int code, String message) {
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

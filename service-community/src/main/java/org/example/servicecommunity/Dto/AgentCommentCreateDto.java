package org.example.servicecommunity.Dto;

import lombok.Data;

/**
 * agent 发表评论请求体（仅支持评论社区帖子；requestId 由 agent 客户端生成）。
 */
@Data
public class AgentCommentCreateDto {
    /** 评论内容，必填（agent 侧上限 2000 字符）。 */
    private String comment;
    /** 帖子 ID，必填。 */
    private Long postId;
    /** 回复某条根评论时传该评论 ID；一级评论不传。 */
    private Long rootCommentId;
    /** 被回复用户 ID，可选。 */
    private Long replyUserId;
    /** 被回复用户名，可选。 */
    private String replyUserName;
    /** 幂等键，必填。 */
    private String requestId;
}

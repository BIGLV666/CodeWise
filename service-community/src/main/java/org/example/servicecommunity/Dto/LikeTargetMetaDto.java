package org.example.servicecommunity.Dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicecommunity.enums.PostType;

/** 点赞目标的作者及页面定位信息，用于 Redis 缓存。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeTargetMetaDto {
    /** 被点赞内容的作者，也是通知接收人。 */
    private Long ownerUserId;
    /** 承载页面的内容 ID：帖子 ID 或题解 ID。 */
    private Long rootId;
    /** 承载页面类型，只会是 POST 或 SOLUTION。 */
    private PostType rootType;
    /** 评论线程的根评论 ID；帖子和题解本身被点赞时为 null。 */
    private Long rootCommentId;
    /** 题解所属题目 ID；跳转帖子页面时为 null。 */
    private Long questionId;
}

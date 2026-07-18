package org.example.serviceapi.dto.notification;

import lombok.Data;

@Data
public class NotificationLikeDto {
    /** 点赞用户 ID，字符串形式避免前端精度丢失。 */
    private String userId;
    /** 点赞用户的展示信息。 */
    private String userName;
    private String nickName;
    private String avatarUrl;
    /**
     * 通知跳转的承载内容 ID：帖子 ID 或题解 ID。
     * 被点赞目标自身的 ID 仍使用外层 NotificationDto.businessId。
     */
    private String rootId;
    /** 承载内容类型：POST 或 SOLUTION。 */
    private String rootType;
    /** 评论所在根评论 ID；非评论通知为 null。 */
    private String rootCommentId;
    /** 题解所属题目 ID；跳转题解页面时使用，帖子通知为 null。 */
    private String questionId;
}

package org.example.serviceapi.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容审核结果通知的扩展数据。
 * 外层 NotificationDto 的 businessType/businessId 表示被审核对象本身，
 * 这里补充跳转所需的承载内容与审核结论。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationCheckedDto {
    /** 审核是否通过。 */
    private Boolean passed;
    /** 是否为下架操作（正常内容被管理员下架）。 */
    private Boolean takeDown;
    /** 被审核内容的标题或摘要，用于通知正文展示。 */
    private String title;
    /** 发送给用户的通知正文，由发送方生成；为空时消费端使用默认模板。 */
    private String message;
    /**
     * 通知跳转的承载内容 ID：帖子 ID 或题解 ID。
     * 评论通知时为评论所属的帖子/题解 ID；帖子和题解通知与 businessId 相同。
     */
    private String rootId;
    /** 承载内容类型：POST 或 SOLUTION。 */
    private String rootType;
    /** 评论所在根评论 ID；非评论通知为 null。 */
    private String rootCommentId;
    /** 题解所属题目 ID；跳转题解页面时使用，帖子通知为 null。 */
    private String questionId;
}

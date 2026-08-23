package org.example.servicecommon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 复习掌握祝贺事件（review -> message，经 Outbox 信封投递）。
 *
 * <p>复习计划状态由 学习中(0) 转为 已掌握(1) 时随 SM-2 更新同事务发布；
 * {@code questionTitle} 由消费端（service-message）异步经 Feign 向 service-question
 * 补齐，生产端不填写。时间为字符串格式（yyyy-MM-dd HH:mm:ss），避免 MQ 消息
 * 转换器缺少 Java 时间模块导致的序列化问题。</p>
 *
 * @param messageId 幂等键，格式 review:mastered:{userId}:{questionId}（掌握为终态，天然唯一）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewMasteredDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：review:mastered:{userId}:{questionId} */
    private String messageId;

    /** 用户 ID */
    private Long userId;

    /** 题目 ID */
    private Long questionId;

    /** 题目名（消费端 Feign 补齐，生产端为空） */
    private String questionTitle;

    /** 掌握时间（yyyy-MM-dd HH:mm:ss） */
    private String masteredTime;

    /** 加入复习计划的时间（yyyy-MM-dd HH:mm:ss，取自 review.create_time） */
    private String joinTime;

    /** 总共复习次数（含本次，取自 review.review_count） */
    private Integer totalReviewCount;
}

package org.example.servicecommunity.Dto;

import lombok.Data;

/**
 * agent 显式点赞/取消点赞请求体。
 *
 * <p>与网页端的「切换」语义不同：agent 声明期望终态（like/unlike），
 * 服务端先查当前状态，已处于期望态则直接幂等返回，避免网络重试造成状态震荡。</p>
 */
@Data
public class AgentLikeRequestDto {
    /** 目标类型：POST 或 COMMENT（题解暂不在 agent 范围内）。 */
    private String targetType;
    /** 目标 ID（帖子 ID 或评论 ID）。 */
    private Long targetId;
    /** 期望终态：like=点赞，unlike=取消点赞。 */
    private String action;
}

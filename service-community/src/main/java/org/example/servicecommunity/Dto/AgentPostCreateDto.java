package org.example.servicecommunity.Dto;

import lombok.Data;

import java.util.List;

/**
 * agent 发帖请求体。
 *
 * <p>requestId 由 agent 客户端生成（UUID），复用网页端的 Redis 幂等键（3 分钟窗口）；
 * userId/userName 一律取自 UserContext，不接受客户端提交。</p>
 */
@Data
public class AgentPostCreateDto {
    /** 帖子标题，必填，1-200 字符。 */
    private String postTitle;
    /** 帖子正文，必填（agent 侧上限 20000 字符）。 */
    private String postContent;
    /** 标签列表，可选，每个标签 <20 字符。 */
    private List<String> tags;
    /** 幂等键，必填。 */
    private String requestId;
}

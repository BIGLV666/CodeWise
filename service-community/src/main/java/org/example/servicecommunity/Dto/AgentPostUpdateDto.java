package org.example.servicecommunity.Dto;

import lombok.Data;

import java.util.List;

/**
 * agent 编辑帖子请求体（postId 经 query 参数传入）。
 *
 * <p>与网页端语义一致：已发布内容编辑后重新进入待审核，审核通过前公开不可见。</p>
 */
@Data
public class AgentPostUpdateDto {
    /** 帖子标题，必填，1-200 字符。 */
    private String postTitle;
    /** 帖子正文，必填（agent 侧上限 20000 字符）。 */
    private String postContent;
    /** 标签列表，可选；传空列表表示清空标签。 */
    private List<String> tags;
}

package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent 创建帖子/评论的结果。
 *
 * <p>duplicate=true 表示 requestId 在幂等窗口内重复提交（本次未创建任何内容），
 * 此时 postId/commentId 为 null——网页端此场景返回 data=null，agent 专用结构把它显式化。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCreatedVo {
    /** 是否为幂等窗口内的重复请求。 */
    private Boolean duplicate;
    /** 新建帖子 ID（评论场景为 null）。 */
    private Long postId;
    /** 新建评论 ID（帖子场景为 null）。 */
    private Long commentId;
    /** 创建后的内容状态：帖子 0=待审核；评论 1=即时可见。 */
    private Integer status;
}

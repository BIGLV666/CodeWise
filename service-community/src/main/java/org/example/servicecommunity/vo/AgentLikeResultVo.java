package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent 显式点赞的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLikeResultVo {
    /** 操作后的终态：true=已点赞，false=未点赞。 */
    private Boolean liked;
    /** 本次是否实际发生了状态变更（false=此前已处于期望态，幂等短路）。 */
    private Boolean changed;
}

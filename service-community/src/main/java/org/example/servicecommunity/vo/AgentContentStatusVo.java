package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent 编辑/删除内容的通用状态结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContentStatusVo {
    /** 内容 ID。 */
    private Long id;
    /** 操作后的状态：帖子编辑后为 0（待审核）；删除为 null。 */
    private Integer status;
    /** 面向模型的补充说明（如审核提醒）。 */
    private String note;
}

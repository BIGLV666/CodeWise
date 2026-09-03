package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * agent 批量删除进度计划的结果（不存在或非本人的 ID 记为 notFound，不外泄他人数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressBatchDeleteVo {
    /** 成功删除的计划数。 */
    private Integer deleted;
    /** 未找到（或无权）的计划 ID。 */
    private List<Long> notFoundIds;
}

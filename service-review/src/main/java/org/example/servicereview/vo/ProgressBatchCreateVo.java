package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * agent 批量创建进度计划的结果（幂等：重复题跳过而非报错）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressBatchCreateVo {
    /** 成功创建的计划数。 */
    private Integer created;
    /** 因已存在同题计划而跳过的题目 ID。 */
    private List<Long> skippedQuestionIds;
}

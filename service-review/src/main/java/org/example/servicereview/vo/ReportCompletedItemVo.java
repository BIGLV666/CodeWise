package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 周报中的单条已完成条目：题目 + 反思总结。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportCompletedItemVo {
    /** 题目 ID。 */
    private Long questionId;
    /** 题目标题（题目服务批量回填，可能为空）。 */
    private String title;
    /** 完成后的反思总结。 */
    private String summaryContent;
}

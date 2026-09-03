package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 周报：某自然周的进度计划汇总，供前端渲染与 agent 组织自然语言总结。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportVo {
    /** 周起始（周一）。 */
    private java.time.LocalDate startDate;
    /** 周结束（周日）。 */
    private java.time.LocalDate endDate;
    /** 本周计划总题数。 */
    private Long totalPlanned;
    /** 本周已完成题数。 */
    private Long completedCount;
    /** 本周进行中（今天）题数。 */
    private Long inProgressCount;
    /** 本周已过期未完成题数。 */
    private Long expiredCount;
    /** 逐日计划/完成数（周一至周日，缺日期补零）。 */
    private java.util.List<ProgressCalendarVo> daily;
    /** 本周已完成条目（含反思），供周报正文。 */
    private java.util.List<ReportCompletedItemVo> completedItems;
}

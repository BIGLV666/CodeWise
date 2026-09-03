package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 进度计划瘦身 VO（agent 列表/今日接口返回）。
 *
 * <p>相比实体 {@link org.example.servicereview.entry.ProgressTracker}：不含 userId 与
 * submit_ids 明细；title/difficulty 经题目瘦身 Feign 批量回填，备注/反思按需截断。
 * 完整信息（含提交记录）走详情接口。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgressTrackerBriefVo {
    /** 计划 ID。 */
    private Long progressId;
    /** 题目 ID。 */
    private Long questionId;
    /** 题目标题（题目服务批量回填，可能为空）。 */
    private String title;
    /** 难度：1-简单 2-中等 3-困难。 */
    private Integer difficulty;
    /** 计划开始日期（哪天做）。 */
    private LocalDate beginTime;
    /** 状态：0-未开始 1-进行中 2-已完成 3-已过期。 */
    private Integer status;
    /** 计划备注（截断）。 */
    private String notesContent;
    /** 反思总结（截断）。 */
    private String summaryContent;
}

package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 进度计划日期聚合条目：某一天的计划题数与完成数。
 * 供前端日期列表展示（天/周/月聚合接口通用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressCalendarVo {
    /** 日期 */
    private LocalDate date;
    /** 当天计划题数 */
    private Long total;
    /** 当天已完成题数（status=2） */
    private Long completed;
}

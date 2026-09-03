package org.example.servicereview.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.ProgressTrackerDto;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.service.ProgressTrackerService;
import org.example.servicereview.vo.ProgressCalendarVo;
import org.example.servicereview.vo.WeeklyReportVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 进度计划追踪：用户自定义题单，按日期学习跟进。
 * <p>日期列表走聚合接口（天/周/月，区间长度随范围变化，不固定天数），
 * 前端点击具体日期后再经 /list 精确查询当天计划。</p>
 */
@RestController
@RequestMapping("/api/review/progress")
public class ProgressTrackerController {

    @Autowired
    private ProgressTrackerService progressTrackerService;

    /** 创建计划（同一题目仅允许一条，重复创建报错）。 */
    @PostMapping("/create")
    @RateLimit(limit = 30, window = 60)
    public Result<ProgressTracker> create(@RequestBody ProgressTrackerDto dto) {
        return Result.success(progressTrackerService.createProgressTracker(dto));
    }

    /** 按日期精确查询当天计划（返回前懒刷新状态）。 */
    @GetMapping("/list")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressTracker>> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(progressTrackerService.listByDate(date));
    }

    /** 日聚合：单日计划题数与完成数。 */
    @GetMapping("/calendar/day")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressCalendarVo>> calendarDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(progressTrackerService.calendarDay(date != null ? date : LocalDate.now()));
    }

    /** 周聚合：锚点日期所在自然周（周一至周日）7 天。 */
    @GetMapping("/calendar/week")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressCalendarVo>> calendarWeek(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(progressTrackerService.calendarWeek(date != null ? date : LocalDate.now()));
    }

    /** 月聚合：锚点日期所在自然月，天数随月份（28-31）。 */
    @GetMapping("/calendar/month")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressCalendarVo>> calendarMonth(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(progressTrackerService.calendarMonth(date != null ? date : LocalDate.now()));
    }

    /** 更新计划：备注/总结任何状态可改；开始时间仅未开始时可改（单向状态机）。 */
    @PostMapping("/update")
    @RateLimit(limit = 30, window = 60)
    public Result<String> update(@RequestParam Long progressId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginTime,
                                 @RequestParam(required = false) String notesContent,
                                 @RequestParam(required = false) String summaryContent) {
        progressTrackerService.updateProgress(progressId, beginTime, notesContent, summaryContent);
        return Result.success("success");
    }

    /** 删除自己的计划。 */
    @PostMapping("/delete")
    @RateLimit(limit = 30, window = 60)
    public Result<String> delete(@RequestParam Long progressId) {
        progressTrackerService.deleteProgress(progressId);
        return Result.success("success");
    }

    /** 周报：锚点日期所在自然周（周一至周日）的进度计划汇总，前端渲染与 agent 总结共用。 */
    @GetMapping("/report/week")
    @RateLimit(limit = 100, window = 60)
    public Result<WeeklyReportVo> reportWeek(@RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(progressTrackerService.getWeeklyReport(date != null ? date : LocalDate.now()));
    }
}

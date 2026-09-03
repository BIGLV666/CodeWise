package org.example.servicereview.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.ProgressTrackerDto;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.service.ProgressTrackerService;
import org.example.servicereview.vo.ProgressBatchCreateVo;
import org.example.servicereview.vo.ProgressBatchDeleteVo;
import org.example.servicereview.vo.ProgressCalendarVo;
import org.example.servicereview.vo.ProgressTrackerBriefVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * codewise-agent 专用进度计划接口（与网页端 {@link ProgressTrackerController} 分离成类）。
 *
 * <p>差异点：</p>
 * <ul>
 *   <li>列表/今日返回瘦身 VO（含题目标题/难度批量回填，备注/反思截断，不含 submitIds）；</li>
 *   <li>批量创建（幂等：同题已存在记为 skipped，不报错）；</li>
 *   <li>批量删除（不存在/非本人记为 notFound，不外泄他人数据）；</li>
 *   <li>路径参数统一用 query 表达，适配 agent 工具的静态路径白名单。</li>
 * </ul>
 *
 * <p>身份安全：与网页端一致，userId 一律取自网关注入的 {@code UserContext}，
 * 计划归属校验在 service 层完成。</p>
 */
@RestController
@RequestMapping("/api/review/agent/progress")
public class AgentProgressTrackerController {

    @Autowired
    private ProgressTrackerService progressTrackerService;

    /** 今天要做的计划（瘦身，状态懒刷新）。 */
    @GetMapping("/today")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressTrackerBriefVo>> today() {
        return Result.success(progressTrackerService.listToday());
    }

    /** 未开始 + 进行中的计划（接下来要做），按日期升序，瘦身返回。 */
    @GetMapping("/list")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressTrackerBriefVo>> listActive() {
        return Result.success(progressTrackerService.listActive());
    }

    /** 单条计划详情（含 submitIds 与完整备注/反思）。 */
    @GetMapping("/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<ProgressTracker> detail(@RequestParam Long progressId) {
        return Result.success(progressTrackerService.getDetail(progressId));
    }

    /** 日历聚合：day/week/month，返回区间内逐日计划数与完成数。 */
    @GetMapping("/calendar")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ProgressCalendarVo>> calendar(
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate anchor = date != null ? date : LocalDate.now();
        List<ProgressCalendarVo> result = switch (range == null ? "week" : range) {
            case "day" -> progressTrackerService.calendarDay(anchor);
            case "week" -> progressTrackerService.calendarWeek(anchor);
            case "month" -> progressTrackerService.calendarMonth(anchor);
            default -> throw new IllegalArgumentException("range 仅支持 day / week / month");
        };
        return Result.success(result);
    }

    /** 批量创建计划（幂等，重复题跳过）。 */
    @PostMapping("/create")
    @RateLimit(limit = 30, window = 60)
    public Result<ProgressBatchCreateVo> batchCreate(@RequestBody List<ProgressTrackerDto> dtos) {
        return Result.success(progressTrackerService.batchCreate(dtos));
    }

    /** 更新计划：备注/总结任何状态可改；开始时间仅未开始可改。 */
    @PostMapping("/update")
    @RateLimit(limit = 30, window = 60)
    public Result<String> update(@RequestParam Long progressId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beginTime,
                                 @RequestParam(required = false) String notesContent,
                                 @RequestParam(required = false) String summaryContent) {
        progressTrackerService.updateProgress(progressId, beginTime, notesContent, summaryContent);
        return Result.success("success");
    }

    /** 批量删除自己的计划。 */
    @PostMapping("/delete")
    @RateLimit(limit = 30, window = 60)
    public Result<ProgressBatchDeleteVo> batchDelete(@RequestParam List<Long> progressIds) {
        return Result.success(progressTrackerService.batchDelete(progressIds));
    }
}

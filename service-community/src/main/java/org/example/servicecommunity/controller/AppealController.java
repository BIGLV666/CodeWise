package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.servicecommunity.Dto.AppealDto;
import org.example.servicecommunity.service.AppealService;
import org.example.servicecommunity.vo.AppealVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 申诉接口，用户侧。
 * <p>身份取自 {@link org.example.servicecommon.until.UserContext}（网关注入），
 * 历史与详情接口仅能访问当前用户自己的申诉。</p>
 */
@RestController
@RequestMapping("/api/community/appeal")
@RateLimit(limit = 30, window = 60)
public class AppealController {

    /** 用户申诉列表单页上限，防止一次性拉取过多。 */
    private static final int MAX_PAGE_SIZE = 50;

    @Autowired
    private AppealService appealService;

    /**
     * 提交申诉
     */
    @PostMapping("/submit")
    public Result<Void> submitAppeal(@RequestBody AppealDto dto) {
        appealService.submitAppeal(dto);
        return Result.success(null);
    }

    /**
     * 当前用户的申诉历史（游标分页，按 appealId 升序）。
     *
     * @param lastId   上一页返回的最大 appealId，首页省略
     * @param pageSize 页大小，默认 10，上限 50
     * @return 申诉分页列表（含关联内容标题，不含申诉人信息）
     */
    @GetMapping("/my")
    public Result<CursorPageResult<AppealVo>> myAppeals(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        return Result.success(appealService.getMyAppeals(lastId, size));
    }

    /**
     * 申诉详情，仅申诉发起人可查看。
     *
     * @param appealId 申诉 ID
     * @return 申诉详情
     */
    @GetMapping("/{appealId}")
    public Result<AppealVo> appealDetail(@PathVariable Long appealId) {
        return Result.success(appealService.getMyAppealDetail(appealId));
    }
}

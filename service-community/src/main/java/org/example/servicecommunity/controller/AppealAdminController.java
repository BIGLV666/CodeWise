package org.example.servicecommunity.controller;

import org.example.apigovernancespringbootstarter.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommunity.service.AppealService;
import org.example.servicecommunity.vo.AppealVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员申诉处理接口
 */
@RestController
@RequestMapping("/api/community/appeal-admin")
@RequireAdmin
@RateLimit(limit = 100, window = 60)
public class AppealAdminController {

    @Autowired
    private AppealService appealService;

    /**
     * 获取申诉列表（分页）
     * @param lastId 游标，上一页最后一条记录的 appealId
     * @param pageSize 每页大小
     * @param status 筛选状态：0-待审核 1-已拒绝 2-已恢复，null 表示全部
     */
    @GetMapping("/list")
    public Result<CursorPageResult<AppealVo>> getAppealList(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Integer status
    ) {
        CursorPageResult<AppealVo> result = appealService.getAppealList(lastId, pageSize, status);
        return Result.success(result);
    }

    /**
     * 处理申诉
     * @param appealId 申诉 ID
     * @param pass true=通过并恢复内容，false=拒绝申诉
     * @param adminReason 管理员回复说明
     */
    @PostMapping("/handle")
    public Result<Void> handleAppeal(
            @RequestParam Long appealId,
            @RequestParam Boolean pass,
            @RequestParam(required = false) String adminReason
    ) {
        appealService.handleAppeal(appealId, pass, adminReason);
        return Result.success(null);
    }
}

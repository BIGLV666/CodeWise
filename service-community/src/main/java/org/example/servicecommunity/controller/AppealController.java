package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.apigovernancespringbootstarter.annotation.RateLimit;
import org.example.servicecommunity.Dto.AppealDto;
import org.example.servicecommunity.service.AppealService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 申诉接口，用户侧
 */
@RestController
@RequestMapping("/api/community/appeal")
@RateLimit(limit = 30, window = 60)
public class AppealController {

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
}

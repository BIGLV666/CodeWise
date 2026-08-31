package org.example.servicejudge.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.service.failure.FailureSubmitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequireAdmin
@RequestMapping("/api/judge/failure")
public class FailureSubmitController {

    private final FailureSubmitService failureSubmitService;

    public FailureSubmitController(FailureSubmitService failureSubmitService) {
        this.failureSubmitService = failureSubmitService;
    }

    @GetMapping("/{status}/list")
    @RateLimit(limit = 30, window = 60)
    public Result<List<FailureSubmit>> getFailureSubmits(@PathVariable Integer status) {
        return Result.success(failureSubmitService.getRecordsByStatus(status));
    }

    @PostMapping("/retry/{id}")
    @RateLimit(limit = 30, window = 60)
    public Result<String> retry(@PathVariable Long id) {
        failureSubmitService.retry(id);
        return Result.success("success");
    }
}

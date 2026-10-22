package org.example.servicequestion.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicequestion.dto.FunctionBatchImportRequest;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.dto.FunctionTestCaseGenerateRequest;
import org.example.servicequestion.service.FunctionBatchImportService;
import org.example.servicequestion.service.FunctionQuestionParseService;
import org.example.servicequestion.service.FunctionTestCaseGenerationService;
import org.example.servicequestion.vo.FunctionBatchImportTaskVo;
import org.example.servicequestion.vo.FunctionParseVo;
import org.example.servicequestion.vo.FunctionTestCaseGenerationTaskVo;
import org.example.servicequestion.service.LeetCodeArtifactImportService;
import org.example.servicequestion.vo.LeetCodeArtifactTaskVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequireAdmin
@RequestMapping("/api/question/function")
public class FunctionQuestionParseController {
    @Autowired
    private FunctionQuestionParseService functionQuestionParseService;
    @Autowired
    private FunctionTestCaseGenerationService functionTestCaseGenerationService;
    @Autowired
    private LeetCodeArtifactImportService leetCodeArtifactImportService;
    @Autowired
    private FunctionBatchImportService functionBatchImportService;

    @GetMapping("/leetcode")
    @RateLimit(limit = 30, window = 60)
    public Result<FunctionParseVo> fromLeetcode(@RequestParam String title) throws IOException, InterruptedException {
        return Result.success(functionQuestionParseService.fromLeetCode(title));
    }

    @PostMapping
    @RateLimit(limit = 20, window = 60)
    public Result<Long> insert(@RequestBody FunctionDto functionDto) {
        return Result.success(functionQuestionParseService.insert(functionDto));
    }

    @PostMapping("/test-cases/batch")
    @RateLimit(limit = 20, window = 60)
    public Result<Integer> insertTestCases(
            @RequestParam Long questionId,
            @RequestBody List<FunctionTestCaseDto> testCases
    ) {
        return Result.success(functionQuestionParseService.insertTestCases(questionId, testCases));
    }

    @PostMapping("/test-cases/generate")
    @RateLimit(limit = 10, window = 60)
    public Result<FunctionTestCaseGenerationTaskVo> generateTestCases(
            @RequestBody FunctionTestCaseGenerateRequest request
    ) {
        return Result.success(functionTestCaseGenerationService.start(request));
    }

    @GetMapping("/test-cases/generate/status")
    @RateLimit(limit = 100, window = 60)
    public Result<FunctionTestCaseGenerationTaskVo> getGenerationStatus(
            @RequestParam String taskId
    ) {
        return Result.success(functionTestCaseGenerationService.getTask(taskId));
    }

    @PostMapping("/leetcode/artifacts/generate")
    @RateLimit(limit = 10, window = 60)
    public Result<LeetCodeArtifactTaskVo> generateLeetCodeArtifacts(
            @RequestParam String titleSlug
    ) {
        return Result.success(leetCodeArtifactImportService.start(titleSlug));
    }
    @GetMapping("/leetcode/artifacts/status")
    @RateLimit(limit = 100, window = 60)
    public Result<LeetCodeArtifactTaskVo> getLeetCodeArtifactStatus(
            @RequestParam String taskId
    ) {
        return Result.success(leetCodeArtifactImportService.getTask(taskId));
    }

    /**
     * 批量导入函数题（管理员）：题面缺省时由 AI 依据解法源码原创重写，
     * 隐藏用例用本条解法当标准答案经判题沙箱生成。任务异步执行，用下面的状态接口轮询。
     */
    @PostMapping("/batch-import")
    @RateLimit(limit = 10, window = 60)
    public Result<FunctionBatchImportTaskVo> batchImport(
            @RequestBody FunctionBatchImportRequest request
    ) {
        return Result.success(functionBatchImportService.start(request));
    }

    @GetMapping("/batch-import/status")
    @RateLimit(limit = 100, window = 60)
    public Result<FunctionBatchImportTaskVo> getBatchImportStatus(
            @RequestParam String taskId
    ) {
        return Result.success(functionBatchImportService.getTask(taskId));
    }

    /**
     * 把最早的若干条隐藏用例提升为公开样例并回填题目样例字段（管理员）。
     * 导入流程在用例生成成功后调用，让页面展示的样例与判题数据一致。
     */
    @PostMapping("/test-cases/sample/{questionId}")
    @RateLimit(limit = 20, window = 60)
    public Result<Integer> promoteTestCasesToSample(
            @PathVariable Long questionId,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false, defaultValue = "false") Boolean publish
    ) {
        return Result.success(functionQuestionParseService.promoteSamples(questionId, count, publish));
    }
}

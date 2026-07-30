package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.dto.FunctionTestCaseGenerateRequest;
import org.example.servicequestion.service.FunctionQuestionParseService;
import org.example.servicequestion.service.FunctionTestCaseGenerationService;
import org.example.servicequestion.vo.FunctionParseVo;
import org.example.servicequestion.vo.FunctionTestCaseGenerationTaskVo;
import org.example.servicequestion.service.LeetCodeArtifactImportService;
import org.example.servicequestion.vo.LeetCodeArtifactTaskVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/question/function")
public class FunctionQuestionParseController {
    @Autowired
    private FunctionQuestionParseService functionQuestionParseService;
    @Autowired
    private FunctionTestCaseGenerationService functionTestCaseGenerationService;
    @Autowired
    private LeetCodeArtifactImportService leetCodeArtifactImportService;

    @GetMapping("/leetcode")
    public Result<FunctionParseVo> fromLeetcode(@RequestParam String title) throws IOException, InterruptedException {
        return Result.success(functionQuestionParseService.fromLeetCode(title));
    }

    @PostMapping
    public Result<Long> insert(@RequestBody FunctionDto functionDto) {
        return Result.success(functionQuestionParseService.insert(functionDto));
    }

    @PostMapping("/test-cases/batch")
    public Result<Integer> insertTestCases(
            @RequestParam Long questionId,
            @RequestBody List<FunctionTestCaseDto> testCases
    ) {
        return Result.success(functionQuestionParseService.insertTestCases(questionId, testCases));
    }

    @PostMapping("/test-cases/generate")
    public Result<FunctionTestCaseGenerationTaskVo> generateTestCases(
            @RequestBody FunctionTestCaseGenerateRequest request
    ) {
        return Result.success(functionTestCaseGenerationService.start(request));
    }

    @GetMapping("/test-cases/generate/status")
    public Result<FunctionTestCaseGenerationTaskVo> getGenerationStatus(
            @RequestParam String taskId
    ) {
        return Result.success(functionTestCaseGenerationService.getTask(taskId));
    }

    @PostMapping("/leetcode/artifacts/generate")
    public Result<LeetCodeArtifactTaskVo> generateLeetCodeArtifacts(
            @RequestParam String titleSlug
    ) {
        return Result.success(leetCodeArtifactImportService.start(titleSlug));
    }

    @GetMapping("/leetcode/artifacts/status")
    public Result<LeetCodeArtifactTaskVo> getLeetCodeArtifactStatus(
            @RequestParam String taskId
    ) {
        return Result.success(leetCodeArtifactImportService.getTask(taskId));
    }
}

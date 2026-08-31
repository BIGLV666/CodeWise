package org.example.servicequestion.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicequestion.dto.InsertTestCaseDto;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.service.TestCaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequireAdmin
@RequestMapping("/api/question")
public class TestCaseController {
    @Autowired
    private TestCaseService testCaseService;

    @GetMapping("/getallquestion")
    @RateLimit(limit = 100, window = 60)
    public Result<List<Map<String,Object>>> getQuestionInfo(){
        return Result.success(testCaseService.getAllTestCase());
    }

    @PostMapping("/addtestcase")
    @RateLimit(limit = 20, window = 60)
    public Result<TestCase> addTestCase(@RequestBody TestCase testCase) {
        return Result.success(testCaseService.addTestCase(testCase));
    }

    @GetMapping("/gettestcasebyid")
    @RateLimit(limit = 100, window = 60)
    public Result<TestCase> getTestCaseById(@RequestParam Long caseId) {
        return Result.success(testCaseService.getTestCaseById(caseId));
    }

    @GetMapping("/gettestcasesbyquestionid")
    @RateLimit(limit = 100, window = 60)
    public Result<List<TestCase>> getTestCasesByQuestionId(@RequestParam Long questionId) {
        return Result.success(testCaseService.getTestCasesByQuestionId(questionId));
    }

    @PutMapping("/updatetestcase")
    @RateLimit(limit = 20, window = 60)
    public Result<TestCase> updateTestCase(@RequestBody InsertTestCaseDto dto, @RequestParam Long caseId) {
        return Result.success(testCaseService.updateTestCase(dto, caseId));
    }

    @DeleteMapping("/deletetestcase")
    @RateLimit(limit = 20, window = 60)
    public Result<Void> deleteTestCase(@RequestParam Long caseId) {
        testCaseService.deleteTestCase(caseId);
        return Result.success("删除成功");
    }
}

package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.dto.InsertTestCaseDto;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.service.TestCaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/question")
public class TestCaseController {
    @Autowired
    private TestCaseService testCaseService;

    @GetMapping("/getallquestion")
    public Result<List<Map<String,Object>>> getQuestionInfo(){
        return Result.success(testCaseService.getAllTestCase());
    }

    @PostMapping("/addtestcase")
    public Result<TestCase> addTestCase(@RequestBody TestCase testCase) {
        return Result.success(testCaseService.addTestCase(testCase));
    }

    @GetMapping("/gettestcasebyid")
    public Result<TestCase> getTestCaseById(@RequestParam Long caseId) {
        return Result.success(testCaseService.getTestCaseById(caseId));
    }

    @GetMapping("/gettestcasesbyquestionid")
    public Result<List<TestCase>> getTestCasesByQuestionId(@RequestParam Long questionId) {
        return Result.success(testCaseService.getTestCasesByQuestionId(questionId));
    }

    @PutMapping("/updatetestcase")
    public Result<TestCase> updateTestCase(@RequestBody InsertTestCaseDto dto, @RequestParam Long caseId) {
        return Result.success(testCaseService.updateTestCase(dto, caseId));
    }

    @DeleteMapping("/deletetestcase")
    public Result<Void> deleteTestCase(@RequestParam Long caseId) {
        testCaseService.deleteTestCase(caseId);
        return Result.success("删除成功");
    }
}

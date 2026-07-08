package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.dto.ReturnQuestionDto;
import org.example.servicequestion.entry.Question;


import org.example.servicequestion.fps.OJImportUtil;
import org.example.servicequestion.service.QuestionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/question")
public class QuestionController {


    @Autowired
    private SimpMessagingTemplate messagingTemplate;


    @Autowired
    private QuestionService questionService;
    @GetMapping("/test-ws")
    public String testWs() {
        messagingTemplate.convertAndSend("/topic/judge-result", "广播测试消息");
        return "广播已发送";
    }


    @PostMapping("/addquestion")
    public Result<Question> addQuestion(@RequestBody InsertQuestionDto insertQuestionDto) {
        Question question = questionService.addQuestion(insertQuestionDto);
        return Result.success(question);
    }

    @GetMapping("/getquestionbyid")
    public Result<Question> getQuestionById(@RequestParam Long questionId)  {
        Question question = questionService.getQuestionById(questionId);

        return Result.success(question);
    }

    @PutMapping("/updatequestion")
    public Result<Question> updateQuestion(@RequestBody InsertQuestionDto insertQuestionDto,@RequestParam Long questionId) {
        Question updated = questionService.updateQuestion(insertQuestionDto,questionId);
        return Result.success(updated);
    }

    @DeleteMapping("/deletequestion")
    public Result<Void> deleteQuestion(@RequestParam Long questionId) {
        questionService.deleteQuestion(questionId);
        return Result.success("删除成功");
    }

    @GetMapping("/cursorquestions")
    public Result<CursorPageResult<ReturnQuestionDto>> cursorQuestions(
            @RequestParam(required = false) Long lastId,
            @RequestParam Integer pageSize,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String title) {
        CursorPageResult<ReturnQuestionDto> result = questionService.cursorQuestions(lastId, pageSize, difficulty, status, title);
        return Result.success(result);
    }
}

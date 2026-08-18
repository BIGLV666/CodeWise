package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.dto.ReturnQuestionDto;
import org.example.servicequestion.entry.Question;


import org.example.servicequestion.fps.OJImportUtil;
import org.example.servicequestion.service.QuestionService;

import org.example.servicequestion.vo.QuestionVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.example.apigovernancespringbootstarter.annotation.RateLimit;

import java.util.List;

@RestController
@RequestMapping("/api/question")
public class QuestionController {

    @Autowired
    private QuestionService questionService;
    @PostMapping("/addquestion")
    @RateLimit(limit = 20, window = 60)
    public Result<Question> addQuestion(@RequestBody InsertQuestionDto insertQuestionDto) {
        Question question = questionService.addQuestion(insertQuestionDto);
        return Result.success(question);
    }

    @GetMapping("/getquestionbyid")
    @RateLimit(limit = 200, window = 60)
    public Result<QuestionVo> getQuestionById(@RequestParam Long questionId)  {
        QuestionVo question = questionService.getQuestionById(questionId);

        return Result.success(question);
    }

    @PutMapping("/updatequestion")
    @RateLimit(limit = 20, window = 60)
    public Result<Question> updateQuestion(@RequestBody InsertQuestionDto insertQuestionDto,@RequestParam Long questionId) {
        Question updated = questionService.updateQuestion(insertQuestionDto,questionId);
        return Result.success(updated);
    }

    @DeleteMapping("/deletequestion")
    @RateLimit(limit = 20, window = 60)
    public Result<Void> deleteQuestion(@RequestParam Long questionId) {
        questionService.deleteQuestion(questionId);
        return Result.success("删除成功");
    }

    @GetMapping("/cursorquestions")
    @RateLimit(limit = 200, window = 60)
    public Result<CursorPageResult<ReturnQuestionDto>> cursorQuestions(
            @RequestParam(required = false) Long lastId,
            @RequestParam Integer pageSize,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String title,
            @RequestParam(required=false)String type
    ) {
        CursorPageResult<ReturnQuestionDto> result = questionService.cursorQuestions(lastId, pageSize, difficulty, status, title, type);
        return Result.success(result);
    }
    @GetMapping("/total")
    @RateLimit(limit = 100, window = 60)
    public Result<Long>getTotal() throws InterruptedException {
        return Result.success(questionService.getTotalQuestionCount());
    }

    /**
     * 标签题目模糊查询题目
     * @param likeKey
     * @return
     */
    @GetMapping("/likeserach")
    @RateLimit(limit = 100, window = 60)
    public Result<List<QuestionVo>>serach(@RequestParam String likeKey){
        return Result.success(questionService.serach(likeKey));
    }
}

package org.example.servicequestion.Feign;

import org.example.serviceapi.dto.QuestionDto;

import org.example.serviceapi.dto.Result;
import org.example.servicequestion.FeugnService.QuestionServiceFeign;
import org.example.servicequestion.service.QuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question")
public class QuestionFegin {
    @Autowired
    private QuestionServiceFeign questionServiceFeign;
    @GetMapping("/info/{questionId}")
    public Result<QuestionDto> getQuestion(@PathVariable Long questionId) {
        return Result.success(questionServiceFeign.getQuestionById(questionId));
    }
}

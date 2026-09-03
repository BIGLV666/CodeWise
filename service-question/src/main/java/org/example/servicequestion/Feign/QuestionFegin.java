package org.example.servicequestion.Feign;

import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.servicequestion.FeugnService.QuestionServiceFeign;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/question")
public class QuestionFegin {
    @Autowired
    private QuestionServiceFeign questionServiceFeign;
    @GetMapping("/info/{questionId}")
    public Result<QuestionDto> getQuestion(@PathVariable Long questionId) {
        return Result.success(questionServiceFeign.getQuestionById(questionId));
    }
    @PostMapping("/info/favoritequestions")
    public Result<List<QuestionDto>> getFavorites(@RequestBody List<Long> questionIds) {
        return Result.success(questionServiceFeign.getAllFavoritesByUserId(questionIds));
    }
    /**
     * 按 ID 批量返回题目瘦身信息（不含题干/样例等大字段），供收藏夹条目列表等服务间查询。
     */
    @PostMapping("/info/briefquestions")
    public Result<List<QuestionBriefDto>> getFavoritesBrief(@RequestBody List<Long> questionIds) {
        return Result.success(questionServiceFeign.getAllFavoritesBrief(questionIds));
    }
}

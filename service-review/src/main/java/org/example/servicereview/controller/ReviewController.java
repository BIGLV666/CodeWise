package org.example.servicereview.controller;

import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.ReviewConfigDto;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/review/review")
public class ReviewController {
    @Autowired
    private ReviewService reviewService;
    /**
     *添加题目到复习计划
     */
    @PostMapping("/addquestiontoreview")
    public Result<String> addReview(@RequestParam Long questionId){
        reviewService.addQuestionToReview(questionId);
        return Result.success("success");
    }
    /**
     * 获取今日复习计划。
     * <p>
     * 兼容旧接口路径，推荐新接口使用 /today。
     * </p>
     */
    @GetMapping("/gettodayreview")
    public Result<List<QuestionDto>> getTodayReviewLegacy(){
        return getTodayReview();
    }

    /**
     * 获取今日复习计划。
     */
    @GetMapping("/today")
    public Result<List<QuestionDto>> getTodayReview(){
        return Result.success(reviewService.getAllQuestions());
    }

    /**
     * 获取当前用户复习配置。
     */
    @GetMapping("/config")
    public Result<ReviewConfig> getReviewConfig(){
        return Result.success(reviewService.getCurrentReviewConfig());
    }

    /**
     * 新增或更新当前用户复习配置。
     */
    @PutMapping("/config")
    public Result<ReviewConfig> updateReviewConfig(@RequestBody ReviewConfigDto reviewConfigDto){
        return Result.success(reviewService.updateReviewConfig(reviewConfigDto));
    }

}

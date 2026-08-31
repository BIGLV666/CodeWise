package org.example.servicereview.controller;

import org.apache.ibatis.annotations.Delete;
import org.example.serviceapi.dto.Result;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.servicereview.dto.ReviewConfigDto;
import org.example.servicereview.dto.UpdateReviewDto;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.entry.ReviewRecord;
import org.example.servicereview.service.ReviewService;
import org.example.servicereview.vo.ReviewRecordVo;
import org.example.servicereview.vo.ReviewVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review/review")
public class ReviewController {
    @Autowired
    private ReviewService reviewService;
    /**
     *添加题目到复习计划
     */
    @PostMapping("/addquestiontoreview")
    @RateLimit(limit = 60, window = 60)
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
    @RateLimit(limit = 100, window = 60)
    public Result<Map<String,Object>> getTodayReviewLegacy(){
        return getTodayReview();
    }

    /**
     * 获取今日复习计划。
     */
    @GetMapping("/today")
    @RateLimit(limit = 100, window = 60)
    public Result<Map<String,Object>> getTodayReview(){
        return Result.success(reviewService.getAllQuestions());
    }

    /**
     * 获取当前用户复习配置。
     */
    @GetMapping("/config")
    @RateLimit(limit = 100, window = 60)
    public Result<ReviewConfig> getReviewConfig(){
        return Result.success(reviewService.getCurrentReviewConfig());
    }

    /**
     * 新增或更新当前用户复习配置。
     */
    @PutMapping("/config")
    @RateLimit(limit = 20, window = 60)
    public Result<ReviewConfig> updateReviewConfig(@RequestBody ReviewConfigDto reviewConfigDto){
        return Result.success(reviewService.updateReviewConfig(reviewConfigDto));
    }
    @GetMapping("/allrecord")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ReviewRecord>> getAllReviewRecord(){
        return Result.success(reviewService.getAllRecord());
    }
    @GetMapping("/record/{reviewrecordId}")
    @RateLimit(limit = 100, window = 60)
    public Result<ReviewRecordVo> getReviewRecord(@PathVariable Long reviewrecordId){
        return Result.success(reviewService.getRecordById(reviewrecordId));
    }
    @PutMapping("/review/{reviewId}")
    @RateLimit(limit = 120, window = 60)
    public Result<Review>updateReview(@RequestBody UpdateReviewDto review, @PathVariable Long reviewId){
        return Result.success(reviewService.updateReview(reviewId, review));
    }
    @GetMapping("/allreview")
    @RateLimit(limit = 100, window = 60)
    public Result<List<ReviewVo>> getAllReview(){
        return Result.success(reviewService.getAllReview());
    }
    @DeleteMapping("/review/{reviewId}")
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteReview(@PathVariable Long reviewId){
        reviewService.deleteReview(reviewId);
        return Result.success("success");
    }
    @GetMapping("/reviewrecord")
    @RateLimit(limit = 100, window = 60)
    public Result<ReviewRecordVo>getReviewRecordByDay(@RequestParam LocalDate day){
        return Result.success(reviewService.getReviewRecordByDay(day));
    }

}

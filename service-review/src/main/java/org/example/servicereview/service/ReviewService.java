package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.entry.ReviewRecord;
import org.example.servicereview.mapper.ReviewConfigMapper;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class ReviewService {
    @Autowired
    private ReviewConfigMapper reviewConfigMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private ReviewRecordMapper reviewRecordMapper;
    @Autowired
    private QuestionFeignClient  questionFeignClient;
    private final BigDecimal EFLOW= BigDecimal.valueOf(1.3);
    private final BigDecimal DEFAULT_EASINESS_FACTOR = BigDecimal.valueOf(2.5);

    /**
     * 获得今天所有的待复习的题目,并生成记录
     */
    public List<QuestionDto>getAllQuestions(){
        ReviewRecord reviewRecord=reviewRecordMapper.getTodayRecord(UserContext.getUserId());
        if(reviewRecord!=null){
            List<Long>questionIds=new ArrayList<>();
            questionIds.addAll(reviewRecord.getPendingReviewQuestionIds());
            questionIds.addAll(reviewRecord.getCompletedReviewQuestionIds());
            Result<List<QuestionDto>>questionDtoResult= questionFeignClient.getFavorites(questionIds);
        if(questionDtoResult.getCode()!=200){
            throw new IllegalArgumentException(questionDtoResult.getMessage());
        }
        return questionDtoResult.getData();
        }
        ReviewConfig reviewConfig=reviewConfigMapper.selectOne(new QueryWrapper<ReviewConfig>().eq("user_id", UserContext.getUserId()));
        if(reviewConfig==null){
             reviewConfig=ReviewConfig.builder().userId(UserContext.getUserId()).reviewCount(Integer.MAX_VALUE)
                    .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
             reviewConfigMapper.insert(reviewConfig);
        }
        List<Review>reviews= reviewMapper.getReviews(reviewConfig.getReviewCount(),UserContext.getUserId());
        List<Long>questionIds=reviews.stream().map(Review::getQuestionId).toList();

        Result<List<QuestionDto>>questionDtoResult= questionFeignClient.getFavorites(questionIds);
        if(questionDtoResult.getCode()!=200){
            throw new IllegalArgumentException(questionDtoResult.getMessage());
        }


        //获取上一天的记录
        ReviewRecord lastRecord=reviewRecordMapper.getlastRecord(UserContext.getUserId());
        //创建记录
        ReviewRecord record=new ReviewRecord();
        record.setUserId(UserContext.getUserId());
        record.setCreateTime(LocalDateTime.now());
        record.setPendingReviewQuestionIds(questionDtoResult.getData().stream().map(QuestionDto::getQuestionId).toList());
        record.setCompletedReviewQuestionIds(Collections.emptyList());
        record.setAcQuestionIds(Collections.emptyList());
        if(lastRecord != null && lastRecord.getCreateTime() != null
                && lastRecord.getReviewDate().equals(LocalDate.now().minusDays(1))){
            record.setReviewDays(lastRecord.getReviewDays()+1);
        }
        else{
            record.setReviewDays(1);
        }
        int r=reviewRecordMapper.insert(record);
        if(r==0){
            throw new IllegalArgumentException("获取今天记录失败请稍后重试");
        }



        return questionDtoResult.getData();
    }

    



    /**
     * 根据 SM-2 算法更新复习间隔和下一次复习时间。
     *
     * quality 建议由 OJ 判题结果换算：
     * 5 - AC，全部通过
     * 4 - 通过率 >= 80%，但未 AC
     * 3 - 通过率 60% ~ 79%
     * 2 - 通过率 30% ~ 59%，或 TLE
     * 1 - 通过率 1% ~ 29%，或 RE
     * 0 - 通过率 0%，全 WA，或 CE
     *
     * SM-2 规则：
     * quality < 3：本次复习失败，连续正确次数清零，1 天后再复习。
     * quality >= 3：连续正确次数 +1；第 1 次间隔 1 天，第 2 次间隔 6 天，之后 interval = 上次间隔 * EF。
     */
    public Review calculateNextReviewInterval(Review review, Integer quality) {
        if (review == null) {
            throw new IllegalArgumentException("复习记录不能为空");
        }
        if (quality == null || quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality 必须在 0 到 5 之间");
        }

        LocalDateTime now = LocalDateTime.now();
        BigDecimal oldEf = review.getEasinessFactor() == null ? DEFAULT_EASINESS_FACTOR : review.getEasinessFactor();
        BigDecimal newEf = calculateEasinessFactor(oldEf, quality);

        int oldRepetitions = review.getRepetitions() == null ? 0 : review.getRepetitions();
        int oldIntervalDays = review.getIntervalDays() == null ? 0 : review.getIntervalDays();

        int newRepetitions;
        int newIntervalDays;

        if (quality < 3) {
            newRepetitions = 0;
            newIntervalDays = 1;
        } else {
            newRepetitions = oldRepetitions + 1;
            if (newRepetitions == 1) {
                newIntervalDays = 1;
            } else if (newRepetitions == 2) {
                newIntervalDays = 6;
            } else {
                newIntervalDays = BigDecimal.valueOf(Math.max(oldIntervalDays, 1))
                        .multiply(newEf)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
            }
        }

        review.setEasinessFactor(newEf);
        review.setRepetitions(newRepetitions);
        review.setIntervalDays(newIntervalDays);
        review.setLastQuality(quality);
        review.setLastReviewTime(now);
        review.setNextReviewTime(now.plusDays(newIntervalDays));
        review.setReviewCount((review.getReviewCount() == null ? 0 : review.getReviewCount()) + 1);
        review.setUpdateTime(now);

        return review;
    }

    /**
     * 计算新的难度因子 EF。
     * 公式：EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
     * EF 最低不低于 1.3。
     */
    private BigDecimal calculateEasinessFactor(BigDecimal oldEf, Integer quality) {
        BigDecimal qDiff = BigDecimal.valueOf(5 - quality);
        BigDecimal change = BigDecimal.valueOf(0.1)
                .subtract(qDiff.multiply(BigDecimal.valueOf(0.08).add(qDiff.multiply(BigDecimal.valueOf(0.02)))));
        BigDecimal newEf = oldEf.add(change).setScale(2, RoundingMode.HALF_UP);
        return newEf.compareTo(EFLOW) < 0 ? EFLOW : newEf;
    }



}

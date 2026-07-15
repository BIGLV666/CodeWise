package org.example.servicereview.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Builder;
import lombok.Data;
import org.example.servicereview.dto.UpdateReviewDto;
import org.example.servicereview.entry.Review;

import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class ReviewVo {

    private Long reviewId;

    /** 用户ID */
    private String userId;

    /** 题目ID */
    private Long questionId;
    private String questionTitle;
    /**
     * 权重，0为默认，1为先展示，用户自己选择是否先复习
     */
    private Integer weight ;

    /**
     * 难度因子 Easiness Factor (EF)
     * SM-2 核心参数，初始值 2.5，最小不低于 1.3
     * EF 越大表示题目对该用户越简单，间隔增长越快
     */
    private BigDecimal easinessFactor ;

    /**
     * 连续正确复习次数 (repetitions / n)
     * 每次质量评分 >=3 时 +1，质量评分 <3 时重置为 0
     */

    private Integer repetitions ;

    /**
     * 当前复习间隔天数 (interval / I)
     * n=1 时 interval=1
     * n=2 时 interval=6
     * n>2 时 interval = 上次interval * EF
     */

    private Integer intervalDays ;

    /**
     * 上一次复习的质量评分 (quality / q)
     * 取值范围 0-5：
     * 5 - 完全记得，且毫不费力ac
     * 4 - 完全记得，略有犹豫>80但未ac
     * 3 - 记得，但比较困难60-80
     * 2 - 记不清，提示后想起40-60
     * 1 - 记错，但看到答案后有印象20-40
     * 0 - 完全不记得 通过样例的百分比小于20%
     */
    private Integer lastQuality;

    /** 上一次复习时间 */
    private LocalDateTime lastReviewTime;

    /** 下一次应复习时间（用于查询今日待复习列表） */
    private LocalDateTime nextReviewTime;


    private Integer reviewCount ;

    /**
     * 复习状态
     * 0 - 学习中
     * 1 - 已掌握（可选：达到一定间隔后标记）
     * 2 - 暂停/搁置（用户主动移出复习计划）
     */

    private Integer status ;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
    public  ReviewVo(Review review) {
        this.reviewId = review.getReviewId();
        this.userId = review.getUserId().toString();
        this.questionId = review.getQuestionId();
        this.reviewCount = review.getReviewCount();
        this.lastQuality = review.getLastQuality();
        this.lastReviewTime = review.getLastReviewTime();
        this.nextReviewTime = review.getNextReviewTime();
        this.updateTime = review.getUpdateTime();
        this.createTime = review.getCreateTime();
        this.updateTime = review.getUpdateTime();
        this.easinessFactor = review.getEasinessFactor();
        this.repetitions = review.getRepetitions();
        this.intervalDays = review.getIntervalDays();
        this.lastQuality = review.getLastQuality();
        this.lastReviewTime = review.getLastReviewTime();
        this.status = review.getStatus();
        this.weight=review.getWeight();
    }
}

package org.example.servicereview.service;

import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SM-2 计算逻辑单元测试：getQuality 判题映射、calculateEasinessFactor 标准公式与下限、
 * calculateNextReviewInterval 间隔推进与掌握判定（均为现状行为断言）。
 */
class Sm2CalculationTest {

    private final ReviewService reviewService = new ReviewService();

    // ==================== getQuality ====================

    @Test
    void getQualityReturnsFiveForAc() {
        assertEquals(5, reviewService.getQuality(buildRecord("AC", 5, 5), defaultConfig()));
    }

    @Test
    void getQualityMapsPassRateBands() {
        ReviewConfig config = defaultConfig();
        assertEquals(4, reviewService.getQuality(buildRecord("WA", 8, 10), config));
        assertEquals(3, reviewService.getQuality(buildRecord("WA", 6, 10), config));
        assertEquals(2, reviewService.getQuality(buildRecord("WA", 3, 10), config));
        assertEquals(1, reviewService.getQuality(buildRecord("WA", 1, 10), config));
        assertEquals(0, reviewService.getQuality(buildRecord("WA", 0, 10), config));
    }

    @Test
    void getQualityTreatsCeByConfig() {
        assertEquals(-1, reviewService.getQuality(buildRecord("CE", null, null),
                configWithCompileError(0)));
        assertEquals(0, reviewService.getQuality(buildRecord("CE", null, null),
                configWithCompileError(1)));
    }

    @Test
    void getQualityIgnoresInternalErrorsAndBadTotals() {
        ReviewJudgeRecordDto systemError = buildRecord("WA", 5, 5);
        systemError.setErrorMessage("代码不能为空");
        assertEquals(-1, reviewService.getQuality(systemError, defaultConfig()));

        assertEquals(0, reviewService.getQuality(buildRecord(null, 5, 5), defaultConfig()));
        assertEquals(0, reviewService.getQuality(buildRecord("WA", null, 5), defaultConfig()));
        assertEquals(0, reviewService.getQuality(buildRecord("WA", 5, 0), defaultConfig()));
    }

    // ==================== calculateEasinessFactor ====================

    @Test
    void easinessFactorFollowsSm2Formula() {
        // q=5：EF + 0.1
        assertEquals(new BigDecimal("2.60"),
                reviewService.calculateEasinessFactor(new BigDecimal("2.5"), 5, new BigDecimal("1.30")));
        // q=0：EF + (0.1 - 5*(0.08+5*0.02)) = EF - 0.8
        assertEquals(new BigDecimal("1.70"),
                reviewService.calculateEasinessFactor(new BigDecimal("2.5"), 0, new BigDecimal("1.30")));
        // q=3：EF + (0.1 - 2*(0.08+2*0.02)) = EF - 0.14
        assertEquals(new BigDecimal("2.36"),
                reviewService.calculateEasinessFactor(new BigDecimal("2.5"), 3, new BigDecimal("1.30")));
    }

    @Test
    void easinessFactorClampsToMinimum() {
        assertEquals(new BigDecimal("1.30"),
                reviewService.calculateEasinessFactor(new BigDecimal("1.35"), 0, new BigDecimal("1.30")));
        // 默认下限重载同样钳制到 1.3（EFLOW 为 BigDecimal.valueOf(1.3)，scale 与字面量不同，用 compareTo）
        assertEquals(0, new BigDecimal("1.30").compareTo(
                reviewService.calculateEasinessFactor(new BigDecimal("1.35"), 0)));
    }

    // ==================== calculateNextReviewInterval ====================

    @Test
    void wrongAnswerResetsRepetitionsAndIntervalToOneDay() {
        Review review = buildReview(0, 5, new BigDecimal("2.5"));

        Review updated = reviewService.calculateNextReviewInterval(review, 2, defaultConfig());

        assertEquals(0, updated.getRepetitions());
        assertEquals(1, updated.getIntervalDays());
        assertEquals(2, updated.getLastQuality());
        assertEquals(1, updated.getReviewCount());
        assertEquals(0, updated.getStatus());
        assertNotNull(updated.getNextReviewTime());
    }

    @Test
    void correctAnswersAdvanceIntervalByFormula() {
        Review review = buildReview(0, 0, new BigDecimal("2.5"));
        ReviewConfig config = defaultConfig();

        Review first = reviewService.calculateNextReviewInterval(review, 5, config);
        assertEquals(1, first.getRepetitions());
        assertEquals(1, first.getIntervalDays());
        assertEquals(new BigDecimal("2.60"), first.getEasinessFactor());

        Review second = reviewService.calculateNextReviewInterval(first, 5, config);
        assertEquals(2, second.getRepetitions());
        assertEquals(6, second.getIntervalDays());
        assertEquals(new BigDecimal("2.70"), second.getEasinessFactor());

        // 第三次起 interval = 上次 interval * 新 EF，四舍五入
        Review third = reviewService.calculateNextReviewInterval(second, 5, config);
        assertEquals(3, third.getRepetitions());
        assertEquals(BigDecimal.valueOf(6).multiply(new BigDecimal("2.80"))
                .setScale(0, java.math.RoundingMode.HALF_UP).intValue(), third.getIntervalDays());
    }

    @Test
    void reachingMasteredIntervalMarksStatusMastered() {
        Review review = buildReview(0, 0, new BigDecimal("2.5"));
        ReviewConfig config = ReviewConfig.builder()
                .masteredIntervalDays(10)
                .minEasinessFactor(new BigDecimal("1.30"))
                .initialEasinessFactor(new BigDecimal("2.50"))
                .build();

        Review first = reviewService.calculateNextReviewInterval(review, 5, config);
        assertEquals(0, first.getStatus());
        Review second = reviewService.calculateNextReviewInterval(first, 5, config);
        assertEquals(0, second.getStatus());
        // 第三次 interval = 6 * 2.8 = 16.8 -> 17，达到阈值 10 后标记已掌握（现状行为）
        Review third = reviewService.calculateNextReviewInterval(second, 5, config);
        assertEquals(1, third.getStatus());
    }

    @Test
    void calculateRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> reviewService.calculateNextReviewInterval(null, 5));
        assertThrows(IllegalArgumentException.class,
                () -> reviewService.calculateNextReviewInterval(buildReview(0, 0, new BigDecimal("2.5")), 6));
        assertThrows(IllegalArgumentException.class,
                () -> reviewService.calculateNextReviewInterval(buildReview(0, 0, new BigDecimal("2.5")), null));
    }

    // ==================== 构造工具 ====================

    private ReviewJudgeRecordDto buildRecord(String status, Integer acTotal, Integer allTotal) {
        ReviewJudgeRecordDto dto = new ReviewJudgeRecordDto();
        dto.setUserId(7L);
        dto.setQuestionId(101L);
        dto.setJudgeRecordId(9L);
        dto.setStatus(status);
        dto.setAcTestTotal(acTotal);
        dto.setAllTestTotal(allTotal);
        return dto;
    }

    private ReviewConfig defaultConfig() {
        return configWithCompileError(1);
    }

    private ReviewConfig configWithCompileError(Integer countCompileError) {
        return ReviewConfig.builder()
                .userId(7L)
                .countCompileError(countCompileError)
                .enableAutoReview(1)
                .minEasinessFactor(new BigDecimal("1.30"))
                .initialEasinessFactor(new BigDecimal("2.50"))
                .masteredIntervalDays(30)
                .build();
    }

    private Review buildReview(Integer repetitions, Integer intervalDays, BigDecimal ef) {
        return Review.builder()
                .reviewId(1L)
                .userId(7L)
                .questionId(101L)
                .repetitions(repetitions)
                .intervalDays(intervalDays)
                .easinessFactor(ef)
                .reviewCount(0)
                .status(0)
                .build();
    }
}

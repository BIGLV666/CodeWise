package org.example.servicereview.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 收藏夹内题目的瘦身 VO（agent 专用接口返回）。
 *
 * <p>只含列表展示所需的元信息；题干、样例、提示等完整详情
 * 由 service-question 的 agent 批量详情接口按需提供。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteQuestionBriefVo {
    /** 题目 ID。 */
    private Long questionId;
    /** 题目标题。 */
    private String title;
    /** 难度：1-简单 2-中等 3-困难。 */
    private Integer difficulty;
    /** 标签（逗号分隔字符串）。 */
    private String tags;
    /** 累计提交数。 */
    private Long totalSubmit;
    /** 累计通过数。 */
    private Long totalAc;
    /** 通过率。 */
    private BigDecimal passRate;
}

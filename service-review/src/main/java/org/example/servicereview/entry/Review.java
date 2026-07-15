package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 复习记录表（基于 SM-2 间隔重复算法）
 * 每一条记录代表：某个用户 对 某道题目 的复习状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("review")
public class Review {

    @TableId(type = IdType.AUTO)
    private Long reviewId;

    /** 用户ID */
    private Long userId;

    /** 题目ID */
    private Long questionId;
    /**
     * 权重，0为默认，1为先展示，用户自己选择是否先复习
     */
    @Builder.Default
    private Integer weight = 0;

    /**
     * 难度因子 Easiness Factor (EF)
     * SM-2 核心参数，初始值 2.5，最小不低于 1.3
     * EF 越大表示题目对该用户越简单，间隔增长越快
     */
    @Builder.Default
    private BigDecimal easinessFactor = new BigDecimal("2.5");

    /**
     * 连续正确复习次数 (repetitions / n)
     * 每次质量评分 >=3 时 +1，质量评分 <3 时重置为 0
     */
    @Builder.Default
    private Integer repetitions = 0;

    /**
     * 当前复习间隔天数 (interval / I)
     * n=1 时 interval=1
     * n=2 时 interval=6
     * n>2 时 interval = 上次interval * EF
     */
    @Builder.Default
    private Integer intervalDays = 0;

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

    /** 累计复习次数（不管对错，每复习一次+1，用于统计） */
    @Builder.Default
    private Integer reviewCount = 0;

    /**
     * 复习状态
     * 0 - 学习中
     * 1 - 已掌握（可选：达到一定间隔后标记）
     * 2 - 暂停/搁置（用户主动移出复习计划）
     */
    @Builder.Default
    private Integer status = 0;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

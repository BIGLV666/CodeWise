package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("review_config")
public class ReviewConfig {

    @TableId(type = IdType.AUTO)
    private Long reviewConfigId;

    /** 用户ID，每个用户一份复习配置 */
    private Long userId;

    /**
     * 每天最多推荐/安排复习的题目数量。
     * 用于控制用户每日复习压力，避免一次性推送过多历史错题。
     * 默认 Integer.MAX_VALUE 表示不限制，由待复习题目数量决定。
     */
    @Builder.Default
    private Integer reviewCount = Integer.MAX_VALUE;

    /**
     * 是否启用自动复习计划。
     * 1 - 启用：题目提交后根据 OJ 判题结果自动更新 SM-2 复习参数与下次复习时间。
     * 0 - 关闭：保留复习记录，但不自动推进复习计划，适合用户临时停用复习功能。
     */
    @Builder.Default
    private Integer enableAutoReview = 1;

    /**
     * 编译失败是否计入一次复习。
     * 1 - 计入：Compile Error 直接按未掌握处理，通常映射为 quality=0，并触发短间隔复习。
     * 0 - 不计入：编译失败只记录提交结果，不更新 SM-2 参数。
     * OJ 场景建议开启，因为编译失败也说明本次解题没有形成可运行答案。
     */
    @Builder.Default
    private Integer countCompileError = 1;

    /**
     * 最低难度因子 EF。
     * SM-2 官方常用下限为 1.3，防止题目因多次失败导致间隔增长能力过低。
     */
    @Builder.Default
    private java.math.BigDecimal minEasinessFactor = new java.math.BigDecimal("1.30");

    /**
     * 初始难度因子 EF。
     * 新加入复习计划的题目默认使用 2.5，后续根据 quality 动态调整。
     */
    @Builder.Default
    private java.math.BigDecimal initialEasinessFactor = new java.math.BigDecimal("2.50");

    /**
     * 掌握判定间隔天数。
     * 当某题 nextReviewTime 对应的 intervalDays 达到该值后，可将 Review.status 标记为 1-已掌握。
     * 仅作为业务判定阈值，不影响 SM-2 原始计算公式。
     */
    @Builder.Default
    private Integer masteredIntervalDays = 30;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}

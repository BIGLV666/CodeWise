package org.example.servicereview.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateReviewDto {
    /**
     * 复习状态
     * 0 - 学习中
     * 1 - 已掌握（可选：达到一定间隔后标记）
     * 2 - 暂停/搁置（用户主动移出复习计划）
     */
    private Integer status ;
    /**
     * 权重，0为默认，1为先展示，用户自己选择是否先复习
     */
    private Integer weight;
    private LocalDateTime nextReviewData;
}

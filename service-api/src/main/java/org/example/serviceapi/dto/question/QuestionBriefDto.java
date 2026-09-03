package org.example.serviceapi.dto.question;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 题目瘦身 DTO：仅保留列表/收藏场景需要的轻量字段。
 *
 * <p>与 {@link QuestionDto} 的区别是不含 description、样例、提示等大字段，
 * 用于收藏夹条目等服务间批量查询，降低跨服务 payload。</p>
 *
 * <p>status 与 createUserId 供调用方做可见性过滤（懒删除）：
 * status 0-下架 1-正常 2-审核中 3-私密。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionBriefDto {

    private Long questionId;
    private String title;
    private Integer difficulty;
    private String tags;

    private Integer status;
    private Long createUserId;

    private Long totalSubmit;
    private Long totalAc;
    private BigDecimal passRate;

    private LocalDateTime createTime;
}

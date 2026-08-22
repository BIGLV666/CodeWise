package org.example.serviceapi.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * AI 判题建议请求 DTO（瘦身后的事件引用）。
 *
 * <p>历史上该 DTO 通过 MQ 携带代码、日志、题目描述等 LONGTEXT 级大字段，
 * 现已拆分：消息只保留 ID 引用，service-ai 消费后通过
 * {@code QuestionFeignClient#getJudgeContext} 按 judgeRecordId 拉取
 * {@code JudgeContextDto} 获取大字段。</p>
 *
 * @param userId     目标用户 ID
 * @param submitId   提交记录 ID
 * @param questionId 题目 ID
 * @param judgeRecordId 判题记录 ID（拉取判题上下文的键）
 * @param messageId  幂等键，格式 ai_advice{questionId}:{userId}:{judgeRecordId}
 * @param language   提交语言
 * @param judgeStatus 判题结果状态（WA/RE/TLE）
 */
@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class AiAdviceWADto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long submitId;
    private Long questionId;
    private Long judgeRecordId;
    private String messageId;
    private String language;
    private String judgeStatus;

}

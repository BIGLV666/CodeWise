package org.example.servicequestion.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量导入单条结果。
 *
 * <p>状态：{@code PENDING}（排队）/ {@code RUNNING}（处理中）/ {@code GENERATING}
 * （题目已建、隐藏用例正在异步生成）/ {@code FAILED}（本条失败，不影响其他条目）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionBatchImportItemVo {
    private String title;
    /** 成功建题后的题目 ID。 */
    private Long questionId;
    /** 隐藏用例生成任务 ID（据此查询既有生成任务接口）。 */
    private String generationTaskId;
    /** 题面是否由 AI 重写生成。 */
    private Boolean descriptionGenerated;
    private String status;
    private String errorMessage;
}

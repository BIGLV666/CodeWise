package org.example.servicequestion.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量导入任务快照。
 *
 * <p>任务本身只负责「建题 + 提交隐藏用例生成」：条目进入 {@code GENERATING} 后，
 * 用例由判题异步产出，调用方按 {@code generationTaskId} 查询既有生成任务接口的进度。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionBatchImportTaskVo {
    private String taskId;
    private Long userId;
    /** RUNNING（处理中）/ SUCCESS（全部条目已提交生成）/ PARTIAL（部分条目失败）/ FAILED（全部失败）。 */
    private String status;
    private Integer total;
    /** 已成功建题并提交用例生成的条目数。 */
    private Integer submitted;
    private Integer failed;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private List<FunctionBatchImportItemVo> items;
}

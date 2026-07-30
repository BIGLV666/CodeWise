package org.example.servicequestion.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionTestCaseGenerationTaskVo {
    private String taskId;
    private Long questionId;
    private Long userId;
    private String status;
    private Integer requestedCount;
    private Integer generatedCount;
    private Long seed;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}

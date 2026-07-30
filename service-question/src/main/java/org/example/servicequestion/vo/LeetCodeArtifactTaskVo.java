package org.example.servicequestion.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LeetCodeArtifactTaskVo {
    private String taskId;
    private String titleSlug;
    private Long questionId;
    private Long userId;
    private String status;
    private Integer generatedCount;
    private Long seed;
    private String artifactDirectory;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}

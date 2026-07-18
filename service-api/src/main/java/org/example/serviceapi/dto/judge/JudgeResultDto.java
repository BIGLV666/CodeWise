package org.example.serviceapi.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeResultDto {
    private Long submissionId;
    private String language;
    private String code;
    private String submitStatus;    // AC/WA/TLE/RE/CE/OLE/MLE/PE
    private Integer failInde;       // 失败索引
    private String expectedOutput;  // 期望输出
    private String actual;          // 实际输出
    private Integer timeUsed;       // 耗时(ms)
    private Integer memoryUsed;     // 内存(KB)
    private String error;// 错误信息
    private String log;
}

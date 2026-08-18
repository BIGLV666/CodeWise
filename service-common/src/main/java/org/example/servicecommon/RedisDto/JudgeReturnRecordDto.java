package org.example.servicecommon.RedisDto;

import lombok.Data;

@Data
public class JudgeReturnRecordDto {
    private Long userId;
    private String errorMsg;
    private String log;
    private String submitStatus;
    private String userOutput;
    private Integer failIndex;

    private String inputData;
    private String expectedOutput;
    private Integer timeUsed;
    private Integer memoryUsed;
}

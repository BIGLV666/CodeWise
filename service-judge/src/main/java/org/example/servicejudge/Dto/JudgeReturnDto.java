package org.example.servicejudge.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JudgeReturnDto {
    Integer exitCode ;
    Integer timeUsed;
    String log;
    String errorMsg;
    String stdout ;
    Integer memoryUsed;
}

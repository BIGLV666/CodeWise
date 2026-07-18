package org.example.serviceapi.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class AiAdviceWADto {
    private Long userId;
    private Long submitId;
    private Long questionId;
    private String messageId;
    private String code;
    private String log;
    private String questionContent;
    private String input;
    private String output;
    private String userOutput;
    private String language;
    private String judgeStatus;

}

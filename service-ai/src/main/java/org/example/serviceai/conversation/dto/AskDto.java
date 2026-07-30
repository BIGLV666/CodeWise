package org.example.serviceai.conversation.dto;

import lombok.Data;

@Data
public class AskDto {
    private Long conversationId;
    private String question;
    private String code;
    private Long userAiConfigId;
    private String modelName;
}

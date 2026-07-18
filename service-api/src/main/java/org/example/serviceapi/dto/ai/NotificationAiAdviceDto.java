package org.example.serviceapi.dto.ai;

import lombok.Data;

@Data
public class NotificationAiAdviceDto {
    private String eventId;
    private Long messageId;
    private Long conversationId;
    private Long submitId;
    private String questionId;
    private String judgeStatus;
    private String aiResponse;
}

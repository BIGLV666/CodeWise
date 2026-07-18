package org.example.serviceapi.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.enums.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationReviewMqDto {
    private String nickname;
    private Long questionId;
    private String questionTitle;
    private LocalDateTime lastReviewTime;
    private ReminderType reminderType;
    private Long total;
}

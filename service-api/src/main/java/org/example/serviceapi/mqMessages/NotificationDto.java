package org.example.serviceapi.mqMessages;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;

import java.time.LocalDateTime;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationDto {
    private String messageId;
    private NotificationCenterType type;
    private BusinessType businessType;
    private Long businessId;
    private Long userId;
    private String extraData;//扩展字段

}

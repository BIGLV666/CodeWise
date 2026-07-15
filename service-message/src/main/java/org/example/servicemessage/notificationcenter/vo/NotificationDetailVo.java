package org.example.servicemessage.notificationcenter.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;

import java.time.LocalDateTime;

@Data
public class NotificationDetailVo {
    private String notificationCenterId;
    private String messageId;
    private String title;
    private String content;
    private NotificationCenterType type;
    private BusinessType businessType;
    private String businessId;
    private String userId;
    private Integer isRead;
    private JsonNode extraData;
    private LocalDateTime createTime;

    public NotificationDetailVo(NotificationCenter notification, JsonNode extraData) {
        this.notificationCenterId = notification.getNotificationCenterId().toString();
        this.messageId = notification.getMessageId();
        this.title = notification.getTitle();
        this.content = notification.getContent();
        this.type = notification.getType();
        this.businessType = notification.getBusinessType();
        this.businessId = notification.getBusinessId() == null ? null : notification.getBusinessId().toString();
        this.userId = notification.getUserId().toString();
        this.isRead = notification.getIsRead();
        this.extraData = extraData;
        this.createTime = notification.getCreateTime();
    }
}

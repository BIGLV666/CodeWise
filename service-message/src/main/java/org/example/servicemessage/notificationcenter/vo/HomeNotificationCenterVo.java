package org.example.servicemessage.notificationcenter.vo;

import lombok.Data;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;

import java.time.LocalDateTime;

@Data
public class HomeNotificationCenterVo {
    private String notificationCenterId;
    private String messageId;
    private String title;
    private NotificationCenterType type;
    private BusinessType businessType;
    private String businessId;
    private String userId;
    private Integer isRead;
    private LocalDateTime createTime;

    public HomeNotificationCenterVo(NotificationCenter notification) {
        this.notificationCenterId = notification.getNotificationCenterId().toString();
        this.messageId = notification.getMessageId();
        this.title = notification.getTitle();
        this.type = notification.getType();
        this.businessType = notification.getBusinessType();
        this.businessId = notification.getBusinessId() == null ? null : notification.getBusinessId().toString();
        this.userId = notification.getUserId().toString();
        this.isRead = notification.getIsRead();
        this.createTime = notification.getCreateTime();
    }
}

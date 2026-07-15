package org.example.servicemessage.notificationcenter.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.mqMessages.NotificationDto;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification_center")
public class NotificationCenter {
    @TableId(type = IdType.AUTO)
    private Long notificationCenterId;
    private String messageId;
    private String title;
    private String content;
    private NotificationCenterType type;
    private BusinessType  businessType;
    private Long businessId;
    private Long userId;
    @Builder.Default
    private Integer isRead=0;//0-未读1-已读
    @Builder.Default
    private Integer isDeleted=0;//0-未删除1-删除
    private String extraData;//扩展字段
    @Builder.Default
    private LocalDateTime createTime=LocalDateTime.now();
    @Builder.Default
    private LocalDateTime updateTime=LocalDateTime.now();
    public  NotificationCenter(NotificationDto notificationDto){
        this.messageId=notificationDto.getMessageId();
        this.businessId=notificationDto.getBusinessId();
        this.userId=notificationDto.getUserId();
        this.businessType=notificationDto.getBusinessType();
        this.extraData=notificationDto.getExtraData();
        this.type=notificationDto.getType();
    }
}

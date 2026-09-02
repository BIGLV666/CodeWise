package org.example.servicereview.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.ReviewMasteredDto;
import org.outboxpro.core.event.EventDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * service-review 的事件路由登记：事件类型 -> 载荷类型 -> RabbitMQ 路由。
 *
 * <p>OutboxPro 经 EventRegistry 解析 {@code publisher.publish(eventType, payload)}
 * 的目标路由；载荷 DTO 保持不加注解，service-api 不引入 outboxpro 依赖。</p>
 */
@Configuration
public class OutboxEventRouteConfig {

    /** 复习提醒通知：review -> message，定时任务每用户一次小事务登记 */
    @Bean
    public EventDefinition<NotificationDto> reviewReminder() {
        return EventDefinition.<NotificationDto>builder()
                .eventType(EventTypes.REVIEW_REMINDER)
                .payloadType(NotificationDto.class)
                .route(MqContexts.NOTIFICATION_EXCHANGE, MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY)
                .build();
    }

    /** 复习掌握祝贺：review -> message，与 SM-2 状态更新同事务 */
    @Bean
    public EventDefinition<ReviewMasteredDto> reviewMastered() {
        return EventDefinition.<ReviewMasteredDto>builder()
                .eventType(EventTypes.REVIEW_MASTERED)
                .payloadType(ReviewMasteredDto.class)
                .route(MqContexts.NOTIFICATION_EXCHANGE, MqContexts.NOTIFICATION_REVIEW_MASTERED_ROUTING_KEY)
                .build();
    }
}

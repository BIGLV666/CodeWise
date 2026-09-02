package org.example.servicecommunity.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.config.MqContexts;
import org.outboxpro.core.event.EventDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * service-community 的事件路由登记：事件类型 -> 载荷类型 -> RabbitMQ 路由。
 *
 * <p>OutboxPro 经 EventRegistry 解析 {@code publisher.publish(eventType, payload)}
 * 的目标路由；载荷 DTO 保持不加注解，service-api 不引入 outboxpro 依赖。</p>
 *
 * <p>约定：与业务数据原子一致的事件走 OutboxPro；事务外的尽力而为发送
 * （点赞/审核通知等 fire-and-forget）仍用 RabbitTemplate 直发。</p>
 */
@Configuration
public class OutboxEventRouteConfig {

    /** 申诉处理结果通知：community -> message，与申诉状态更新同事务 */
    @Bean
    public EventDefinition<NotificationDto> notificationAppeal() {
        return EventDefinition.<NotificationDto>builder()
                .eventType(EventTypes.NOTIFICATION_APPEAL)
                .payloadType(NotificationDto.class)
                .route(MqContexts.NOTIFICATION_EXCHANGE, MqContexts.NOTIFICATION_APPEAL_ROUTING_KEY)
                .build();
    }
}

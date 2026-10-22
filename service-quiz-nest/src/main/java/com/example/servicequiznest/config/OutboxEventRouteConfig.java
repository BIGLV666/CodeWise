package com.example.servicequiznest.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.JoinToken;
import org.example.servicecommon.config.MqContexts;
import org.outboxpro.core.event.EventDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * service-quiz-nest 的事件路由登记：事件类型 -> 载荷类型 -> RabbitMQ 路由。
 *
 * <p>邀请码必须与「码落 Redis」这一步保持一致，且站内信不能静默丢失，因此走
 * OutboxPro（{@code outboxpro_outbox}）而不是直接 {@code RabbitTemplate}。
 * 消费方是 service-message 的 {@code notification.queue}，所以路由必须指向
 * {@link MqContexts#NOTIFICATION_EXCHANGE}，而不是邮件队列的 message.exchange。</p>
 */
@Configuration
public class OutboxEventRouteConfig {

    /** 小组邀请码：quiz-nest -> message，payload 为 {@link JoinToken}。 */
    @Bean
    public EventDefinition<JoinToken> groupJoinToken() {
        return EventDefinition.<JoinToken>builder()
                .eventType(EventTypes.GROUP_JOIN_TOKEN)
                .payloadType(JoinToken.class)
                .route(MqContexts.NOTIFICATION_EXCHANGE, MqContexts.NOTIFICATION_JOIN_TOKEN_ROUTING_KEY)
                .build();
    }
}

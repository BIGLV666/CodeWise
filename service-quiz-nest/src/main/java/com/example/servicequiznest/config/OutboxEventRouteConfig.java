package com.example.servicequiznest.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.GroupQuestionCleanupDto;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
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
 *
 * <p>题目回滚事件消费方是 service-question 的 {@code question.queue}，路由指向
 * {@link MqContexts#Question_EXCHANGE}；本地关联写入失败时补偿消息同样不能丢，
 * 因此与业务失败同事务写入 outbox。</p>
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

    /** 小组自建题目回滚：quiz-nest -> question，payload 为 {@link QuestionRollbackDto}。 */
    @Bean
    public EventDefinition<QuestionRollbackDto> questionCreateRollback() {
        return EventDefinition.<QuestionRollbackDto>builder()
                .eventType(EventTypes.QUESTION_CREATE_ROLLBACK)
                .payloadType(QuestionRollbackDto.class)
                .route(MqContexts.Question_EXCHANGE, MqContexts.QUESTION_ROLLBACK_ROUTING_KEY)
                .build();
    }

    /** 小组题库清理：quiz-nest -> question，payload 为 {@link GroupQuestionCleanupDto}。 */
    @Bean
    public EventDefinition<GroupQuestionCleanupDto> groupQuestionCleanup() {
        return EventDefinition.<GroupQuestionCleanupDto>builder()
                .eventType(EventTypes.GROUP_QUESTION_CLEANUP)
                .payloadType(GroupQuestionCleanupDto.class)
                .route(MqContexts.Question_EXCHANGE, MqContexts.QUESTION_GROUP_CLEANUP_ROUTING_KEY)
                .build();
    }
}

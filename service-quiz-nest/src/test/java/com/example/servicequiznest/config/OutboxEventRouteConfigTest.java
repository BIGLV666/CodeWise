package com.example.servicequiznest.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
import org.example.servicecommon.config.MqContexts;
import org.junit.jupiter.api.Test;
import org.outboxpro.core.event.EventDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/** 回滚事件的 Outbox 路由必须指向题目队列，避免事件发到无人消费的交换机。 */
class OutboxEventRouteConfigTest {

    @Test
    void rollbackRoutePointsToQuestionQueue() {
        EventDefinition<QuestionRollbackDto> definition =
                new OutboxEventRouteConfig().questionCreateRollback();

        assertThat(definition.getEventType()).isEqualTo(EventTypes.QUESTION_CREATE_ROLLBACK);
        assertThat(definition.getPayloadType()).isEqualTo(QuestionRollbackDto.class);
        assertThat(definition.getRoute().exchange()).isEqualTo(MqContexts.Question_EXCHANGE);
        assertThat(definition.getRoute().routingKey()).isEqualTo(MqContexts.QUESTION_ROLLBACK_ROUTING_KEY);
    }

    @Test
    void cleanupRoutePointsToQuestionQueue() {
        org.outboxpro.core.event.EventDefinition<org.example.serviceapi.dto.question.GroupQuestionCleanupDto> definition =
                new OutboxEventRouteConfig().groupQuestionCleanup();

        assertThat(definition.getEventType()).isEqualTo(EventTypes.GROUP_QUESTION_CLEANUP);
        assertThat(definition.getPayloadType())
                .isEqualTo(org.example.serviceapi.dto.question.GroupQuestionCleanupDto.class);
        assertThat(definition.getRoute().exchange()).isEqualTo(MqContexts.Question_EXCHANGE);
        assertThat(definition.getRoute().routingKey())
                .isEqualTo(MqContexts.QUESTION_GROUP_CLEANUP_ROUTING_KEY);
    }
}

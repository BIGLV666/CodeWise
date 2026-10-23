package com.example.servicequiznest.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 回滚事件的发布内容与事务传播级别回归护栏。 */
class GroupQuestionRollbackPublisherTest {

    @Test
    void publishesRollbackEventWithPayload() {
        OutboxProPublisher outbox = mock(OutboxProPublisher.class);

        new GroupQuestionRollbackPublisher(outbox).publish(123L, 88L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(EventTypes.QUESTION_CREATE_ROLLBACK), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(QuestionRollbackDto.class);
        QuestionRollbackDto dto = (QuestionRollbackDto) payload.getValue();
        assertThat(dto.getQuestionId()).isEqualTo(123L);
        assertThat(dto.getGroupId()).isEqualTo(88L);
        assertThat(dto.getOccurredAt()).isNotBlank();
    }

    @Test
    void publishRunsInNewTransaction() throws NoSuchMethodException {
        Method method = GroupQuestionRollbackPublisher.class.getMethod("publish", Long.class, Long.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}

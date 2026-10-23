package com.example.servicequiznest.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.GroupQuestionCleanupDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 清理事件的发布内容与「必须处于删组事务」的传播级别回归护栏。 */
class GroupQuestionCleanupPublisherTest {

    @Test
    void publishesCleanupEventWithCustomQuestionIds() {
        OutboxProPublisher outbox = mock(OutboxProPublisher.class);

        new GroupQuestionCleanupPublisher(outbox).publish(88L, List.of(10L, 20L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(EventTypes.GROUP_QUESTION_CLEANUP), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(GroupQuestionCleanupDto.class);
        GroupQuestionCleanupDto dto = (GroupQuestionCleanupDto) payload.getValue();
        assertThat(dto.getGroupId()).isEqualTo(88L);
        assertThat(dto.getQuestionIds()).containsExactly(10L, 20L);
        assertThat(dto.getOccurredAt()).isNotBlank();
    }

    @Test
    void publishRequiresActiveTransaction() throws NoSuchMethodException {
        Method method = GroupQuestionCleanupPublisher.class.getMethod("publish", Long.class, List.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}

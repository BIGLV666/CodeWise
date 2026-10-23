package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.service.QuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小组自建题目回滚消费者：信封/裸格式双读、按 id 幂等删除、异常时手动 settle。
 */
@ExtendWith(MockitoExtension.class)
class RollbackQuestionTest {

    @Mock
    private QuestionService questionService;

    @Mock
    private Channel channel;

    private RollbackQuestion handler;

    @BeforeEach
    void setUp() {
        handler = new RollbackQuestion(questionService);
    }

    @Test
    void routingKeyRegistered() {
        assertThat(handler.getRoutingKey()).isEqualTo(MqContexts.QUESTION_ROLLBACK_ROUTING_KEY);
    }

    @Test
    void deletesCreatedQuestionAndAcks() throws Exception {
        when(questionService.rollbackCreatedQuestion(123L)).thenReturn(true);

        handler.handle(envelope(123L), channel, message(7L));

        verify(questionService).rollbackCreatedQuestion(123L);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void alreadyDeletedQuestionStillAcks() throws Exception {
        when(questionService.rollbackCreatedQuestion(123L)).thenReturn(false);

        handler.handle(envelope(123L), channel, message(8L));

        verify(channel).basicAck(8L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void invalidBodyIsDroppedWithoutRequeue() throws Exception {
        handler.handle("not-json", channel, message(9L));

        verify(channel).basicNack(9L, false, false);
        verify(questionService, never()).rollbackCreatedQuestion(any());
    }

    @Test
    void missingQuestionIdIsDroppedWithoutRequeue() throws Exception {
        handler.handle(envelope(null), channel, message(10L));

        verify(channel).basicNack(10L, false, false);
        verify(questionService, never()).rollbackCreatedQuestion(any());
    }

    @Test
    void deleteFailureIsNackedWithoutRequeue() throws Exception {
        when(questionService.rollbackCreatedQuestion(123L)).thenThrow(new RuntimeException("db down"));

        handler.handle(envelope(123L), channel, message(11L));

        verify(channel).basicNack(11L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private static String envelope(Long questionId) {
        QuestionRollbackDto payload = QuestionRollbackDto.builder()
                .questionId(questionId)
                .groupId(88L)
                .reason("test")
                .occurredAt("2026-10-23 12:00:00")
                .build();
        return EnvelopeCodec.serialize(
                EnvelopeCodec.wrap("QUESTION_CREATE_ROLLBACK", "service-quiz-nest", null, payload));
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}

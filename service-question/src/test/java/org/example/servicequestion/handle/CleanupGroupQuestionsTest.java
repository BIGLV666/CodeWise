package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.question.GroupQuestionCleanupDto;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 删组清题库消费者：批量删除、空列表直接 ACK、异常手动 settle。 */
@ExtendWith(MockitoExtension.class)
class CleanupGroupQuestionsTest {

    @Mock
    private QuestionService questionService;

    @Mock
    private Channel channel;

    private CleanupGroupQuestions handler;

    @BeforeEach
    void setUp() {
        handler = new CleanupGroupQuestions(questionService);
    }

    @Test
    void routingKeyRegistered() {
        assertThat(handler.getRoutingKey()).isEqualTo(MqContexts.QUESTION_GROUP_CLEANUP_ROUTING_KEY);
    }

    @Test
    void deletesCustomQuestionsAndAcks() throws Exception {
        when(questionService.deleteQuestionsBatch(List.of(1L, 2L))).thenReturn(2);

        handler.handle(envelope(List.of(1L, 2L)), channel, message(5L));

        verify(questionService).deleteQuestionsBatch(List.of(1L, 2L));
        verify(channel).basicAck(5L, false);
    }

    @Test
    void emptyQuestionIdsAcksWithoutCallingService() throws Exception {
        handler.handle(envelope(List.of()), channel, message(6L));

        verify(questionService, never()).deleteQuestionsBatch(any());
        verify(channel).basicAck(6L, false);
    }

    @Test
    void invalidBodyIsDroppedWithoutRequeue() throws Exception {
        handler.handle("not-json", channel, message(7L));

        verify(channel).basicNack(7L, false, false);
        verify(questionService, never()).deleteQuestionsBatch(any());
    }

    @Test
    void deleteFailureIsNackedWithoutRequeue() throws Exception {
        when(questionService.deleteQuestionsBatch(any()))
                .thenThrow(new RuntimeException("db down"));

        handler.handle(envelope(List.of(1L)), channel, message(8L));

        verify(channel).basicNack(8L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private static String envelope(List<Long> questionIds) {
        GroupQuestionCleanupDto payload = GroupQuestionCleanupDto.builder()
                .groupId(88L)
                .questionIds(questionIds)
                .reason("test")
                .occurredAt("2026-10-23 20:00:00")
                .build();
        return EnvelopeCodec.serialize(
                EnvelopeCodec.wrap("GROUP_QUESTION_CLEANUP", "service-quiz-nest", null, payload));
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}

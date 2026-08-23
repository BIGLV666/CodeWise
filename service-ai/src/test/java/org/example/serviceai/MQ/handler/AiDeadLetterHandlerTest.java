package org.example.serviceai.MQ.handler;

import com.rabbitmq.client.Channel;
import org.example.serviceai.service.ConsumedEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AI 死信处理器单元测试：信封死信标记终态 FAILED（不覆盖已完成）、
 * 裸格式死信仅留痕、标记失败仍 ACK 不重投。
 */
@ExtendWith(MockitoExtension.class)
class AiDeadLetterHandlerTest {

    @Mock
    private ConsumedEventService consumedEventService;
    @Mock
    private Channel channel;

    @InjectMocks
    private AiDeadLetterHandler handler;

    @Test
    void envelopeDeadLetterIsMarkedFailedAndAcknowledged() throws IOException {
        String envelope = "{\"eventId\":\"evt-9\",\"eventType\":\"AI_WA_ADVICE_REQUEST\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2026-08-23T00:00:00\","
                + "\"producer\":\"service-judge\",\"traceId\":\"t-1\",\"payload\":{}}";

        handler.consumeDeadMessage(messageOf(envelope), channel);

        verify(consumedEventService).markFailedIfNotCompleted("evt-9", "dead-letter after retries");
        verify(channel).basicAck(7L, false);
    }

    @Test
    void bareDeadLetterIsOnlyLoggedAndAcknowledged() throws IOException {
        // testcase 等裸格式消息提取不到 eventId：只留痕，不标记
        String bare = "{\"questionId\":20,\"questionTitle\":\"题目\"}";

        handler.consumeDeadMessage(messageOf(bare), channel);

        verify(consumedEventService, never()).markFailedIfNotCompleted(anyString(), anyString());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void markingFailureStillAcknowledges() throws IOException {
        String envelope = "{\"eventId\":\"evt-9\",\"eventType\":\"AI_WA_ADVICE_REQUEST\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2026-08-23T00:00:00\","
                + "\"producer\":\"service-judge\",\"traceId\":\"t-1\",\"payload\":{}}";
        doThrow(new IllegalStateException("db down"))
                .when(consumedEventService).markFailedIfNotCompleted(anyString(), anyString());

        handler.consumeDeadMessage(messageOf(envelope), channel);

        // 死信队列无下游：标记失败不重投，原始 body 已在日志留痕
        verify(channel).basicAck(7L, false);
    }

    private Message messageOf(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

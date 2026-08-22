package org.example.servicejudge.Mq.consumer;

import com.rabbitmq.client.Channel;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicejudge.Mq.handler.JudgeSubmitHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 提交消费者单元测试：解析失败死信、业务异常延迟重试（等待队列 + 指数退避 +
 * retryCount 递增）、重试超限死信、成功 ACK 与信封双读。
 */
@ExtendWith(MockitoExtension.class)
class JudgeSubmitConsumerTest {

    @Mock
    private JudgeSubmitHandler judgeSubmitHandler;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;

    private JudgeSubmitConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new JudgeSubmitConsumer(judgeSubmitHandler, rabbitTemplate);
    }

    @Test
    void poisonMessageGoesStraightToDeadLetter() throws IOException {
        Message message = messageOf("{not-json", new MessageProperties());

        consumer.onMessage(message, channel);

        verify(channel).basicNack(7L, false, false);
        verifyNoInteractions(judgeSubmitHandler, rabbitTemplate);
    }

    @Test
    void businessFailureWithoutRetryHeaderDefersToWaitQueue() throws IOException {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message message = messageOf("42", properties);
        doThrow(new IllegalStateException("boom")).when(judgeSubmitHandler).handle(42L);

        consumer.onMessage(message, channel);

        ArgumentCaptor<Message> retryCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(MqContexts.JUDGE_WAIT_QUEUE), retryCaptor.capture());
        verify(channel).basicAck(7L, false);

        Message retried = retryCaptor.getValue();
        assertArrayEquals("42".getBytes(StandardCharsets.UTF_8), retried.getBody());
        assertEquals(Integer.valueOf(1), retried.getMessageProperties().getHeader(EventPublisher.HEADER_RETRY_COUNT));
        assertEquals("5000", retried.getMessageProperties().getExpiration());
        assertEquals("application/json", retried.getMessageProperties().getContentType());
        verify(channel, never()).basicNack(7L, false, false);
    }

    @Test
    void businessFailureExponentiallyBacksOffWithExistingRetryCount() throws IOException {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EventPublisher.HEADER_RETRY_COUNT, 1);
        Message message = messageOf("42", properties);
        doThrow(new IllegalStateException("boom")).when(judgeSubmitHandler).handle(42L);

        consumer.onMessage(message, channel);

        ArgumentCaptor<Message> retryCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(MqContexts.JUDGE_WAIT_QUEUE), retryCaptor.capture());
        verify(channel).basicAck(7L, false);

        Message retried = retryCaptor.getValue();
        assertEquals(Integer.valueOf(2), retried.getMessageProperties().getHeader(EventPublisher.HEADER_RETRY_COUNT));
        assertEquals("10000", retried.getMessageProperties().getExpiration());
    }

    @Test
    void exhaustedRetryCountDeadLetters() throws IOException {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EventPublisher.HEADER_RETRY_COUNT, 3);
        Message message = messageOf("42", properties);
        doThrow(new IllegalStateException("boom")).when(judgeSubmitHandler).handle(42L);

        consumer.onMessage(message, channel);

        verify(channel).basicNack(7L, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void successAcknowledges() throws IOException {
        Message message = messageOf("42", new MessageProperties());

        consumer.onMessage(message, channel);

        verify(judgeSubmitHandler).handle(42L);
        verify(channel).basicAck(7L, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void envelopePayloadIsUnwrappedToBareLong() throws IOException {
        String envelope = "{\"eventId\":\"e-1\",\"eventType\":\"JUDGE_SUBMIT_REQUEST\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2026-08-22T00:00:00\",\"producer\":\"service-question\","
                + "\"traceId\":\"t-1\",\"payload\":42}";
        Message message = messageOf(envelope, new MessageProperties());

        consumer.onMessage(message, channel);

        verify(judgeSubmitHandler).handle(42L);
        verify(channel).basicAck(7L, false);
    }

    private Message messageOf(String body, MessageProperties properties) {
        properties.setDeliveryTag(7L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

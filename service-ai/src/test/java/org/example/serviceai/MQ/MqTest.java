package org.example.serviceai.MQ;

import com.rabbitmq.client.Channel;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EventPublisher;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 分发器单元测试：成功 ACK、毒消息死信、业务异常按路由键延迟重试
 * （等待队列 + 指数退避 + retryCount 递增）、重试超限死信、无 handler 死信。
 */
@ExtendWith(MockitoExtension.class)
class MqTest {

    @Mock
    private AiMessageHandler handler;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;

    private Mq mq;

    @BeforeEach
    void setUp() {
        mq = new Mq(List.of(handler), rabbitTemplate);
        when(handler.getRoutingKey()).thenReturn(MqContexts.AI_WA_ADVICE_ROUTING_KEY);
        mq.init();
    }

    @Test
    void successAcknowledges() throws Exception {
        mq.mq(MqContexts.AI_WA_ADVICE_ROUTING_KEY, channel,
                messageOf("{\"messageId\":\"m-1\"}", new MessageProperties()));

        verify(handler).handle(eq("{\"messageId\":\"m-1\"}"), any(Message.class));
        verify(channel).basicAck(7L, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void poisonMessageGoesStraightToDeadLetter() throws Exception {
        doThrow(new IllegalArgumentException("bad payload"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq(MqContexts.AI_WA_ADVICE_ROUTING_KEY, channel,
                messageOf("{not-json", new MessageProperties()));

        verify(channel).basicNack(7L, false, false);
        verify(channel, never()).basicAck(7L, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void businessFailureDefersToAdviceWaitQueue() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        doThrow(new IllegalStateException("boom"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq(MqContexts.AI_WA_ADVICE_ROUTING_KEY, channel,
                messageOf("{\"messageId\":\"m-1\"}", properties));

        ArgumentCaptor<Message> retryCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(MqContexts.AI_ADVICE_WAIT_QUEUE), retryCaptor.capture());
        verify(channel).basicAck(7L, false);

        Message retried = retryCaptor.getValue();
        assertArrayEquals("{\"messageId\":\"m-1\"}".getBytes(StandardCharsets.UTF_8), retried.getBody());
        assertEquals(Integer.valueOf(1), retried.getMessageProperties().getHeader(EventPublisher.HEADER_RETRY_COUNT));
        assertEquals("5000", retried.getMessageProperties().getExpiration());
        assertEquals("application/json", retried.getMessageProperties().getContentType());
        verify(channel, never()).basicNack(7L, false, false);
    }

    @Test
    void businessFailureBacksOffExponentiallyWithExistingRetryCount() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EventPublisher.HEADER_RETRY_COUNT, 1);
        doThrow(new IllegalStateException("boom"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq(MqContexts.AI_WA_ADVICE_ROUTING_KEY, channel,
                messageOf("{\"messageId\":\"m-1\"}", properties));

        ArgumentCaptor<Message> retryCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(MqContexts.AI_ADVICE_WAIT_QUEUE), retryCaptor.capture());
        verify(channel).basicAck(7L, false);

        Message retried = retryCaptor.getValue();
        assertEquals(Integer.valueOf(2), retried.getMessageProperties().getHeader(EventPublisher.HEADER_RETRY_COUNT));
        assertEquals("10000", retried.getMessageProperties().getExpiration());
    }

    @Test
    void testcaseRoutingDefersToTestcaseWaitQueue() throws Exception {
        when(handler.getRoutingKey()).thenReturn(MqContexts.Ai_TESTCASE_ROUTING_KEY);
        mq.init();
        doThrow(new IllegalStateException("boom"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq(MqContexts.Ai_TESTCASE_ROUTING_KEY, channel,
                messageOf("{\"questionId\":1}", new MessageProperties()));

        verify(rabbitTemplate).send(eq(""), eq(MqContexts.AI_TESTCASE_WAIT_QUEUE), any(Message.class));
        verify(channel).basicAck(7L, false);
    }

    @Test
    void exhaustedRetryCountDeadLetters() throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(EventPublisher.HEADER_RETRY_COUNT, 3);
        doThrow(new IllegalStateException("boom"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq(MqContexts.AI_WA_ADVICE_ROUTING_KEY, channel,
                messageOf("{\"messageId\":\"m-1\"}", properties));

        verify(channel).basicNack(7L, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void missingHandlerDeadLetters() throws IOException {
        mq.mq("ai.unknown.routing", channel,
                messageOf("{\"messageId\":\"m-1\"}", new MessageProperties()));

        verify(channel).basicNack(7L, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void unknownRoutingKeyFailureDeadLettersInsteadOfHanging() throws Exception {
        // 路由键不在两个主队列路由键之内：无等待队列可投，按死信处理避免消息悬挂
        when(handler.getRoutingKey()).thenReturn("ai.unknown.routing");
        mq.init();
        doThrow(new IllegalStateException("boom"))
                .when(handler).handle(anyString(), any(Message.class));

        mq.mq("ai.unknown.routing", channel,
                messageOf("{\"messageId\":\"m-1\"}", new MessageProperties()));

        verify(channel).basicNack(7L, false, false);
        verifyNoInteractions(rabbitTemplate);
    }

    private Message messageOf(String body, MessageProperties properties) {
        properties.setDeliveryTag(7L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

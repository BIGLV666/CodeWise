package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.ai.NotificationAiAdviceDto;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAdviceHandleTest {
    private static final long DELIVERY_TAG = 7L;
    private static final String MESSAGE_ID = "advice-1";
    private static final String BODY =
            "{\"messageId\":\"advice-1\",\"userId\":1,\"extraData\":\"{\\\"judgeStatus\\\":\\\"WRONG_ANSWER\\\"}\"}";

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private WebSocketPushService webSocketPushService;
    @Mock
    private ConsumedEventService consumedEventService;
    @Mock
    private Channel channel;

    @InjectMocks
    private AiAdviceHandle aiAdviceHandle;

    @Test
    void duplicateCompletedEventIsAcknowledgedWithoutPush() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, aiAdviceHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);

        aiAdviceHandle.handle(BODY, channel, amqpMessage(BODY));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(webSocketPushService);
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void successfulPushCompletesEventAndAcknowledges() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, aiAdviceHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);

        aiAdviceHandle.handle(BODY, channel, amqpMessage(BODY));

        verify(webSocketPushService).pushToUserQueue(eq(1L), eq("AI_ADVICE"), any(NotificationAiAdviceDto.class));
        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void firstFailureRequeuesForRetry() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, aiAdviceHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        doThrow(new RuntimeException("push down")).when(webSocketPushService)
                .pushToUserQueue(anyLong(), anyString(), any());
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(1);

        aiAdviceHandle.handle(BODY, channel, amqpMessage(BODY));

        verify(consumedEventService, never()).complete(anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    @Test
    void thirdFailureMarksFailedAndDiscards() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, aiAdviceHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        doThrow(new RuntimeException("push down")).when(webSocketPushService)
                .pushToUserQueue(anyLong(), anyString(), any());
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(3);

        aiAdviceHandle.handle(BODY, channel, amqpMessage(BODY));

        verify(consumedEventService).markFailed(eq(MESSAGE_ID), anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void invalidPayloadIsDeadLetteredWithoutClaim() throws IOException {
        String body = "not-json";

        aiAdviceHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(webSocketPushService);
    }

    private Message amqpMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.ApproveResult;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组审批结果消费端测试：收件箱字段与文案、Outbox 信封/裸消息双读、
 * consumed_event 幂等、失败重试/超限留存、毒消息丢弃、WebSocket 尽力推送。
 */
@ExtendWith(MockitoExtension.class)
class GroupApproveResultHandleTest {

    private static final long DELIVERY_TAG = 31L;
    private static final Long GROUP_ID = 99L;
    private static final Long TO_USER_ID = 7L;
    private static final String MESSAGE_ID = "quiz:approve:00000000-0000-0000-0000-000000000002";

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private ConsumedEventService consumedEventService;
    @Mock
    private NotificationCenterMapper notificationCenterMapper;
    @Mock
    private WebSocketPushService webSocketPushService;
    @Mock
    private Channel channel;

    @InjectMocks
    private GroupApproveResultHandle handler;

    @Test
    void successfulChainInsertsNotificationPushesCompletesAndAcks() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        ArgumentCaptor<NotificationCenter> captor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper).insert(captor.capture());
        NotificationCenter notification = captor.getValue();
        assertEquals(MESSAGE_ID, notification.getMessageId());
        assertEquals(TO_USER_ID, notification.getUserId());
        assertEquals(NotificationCenterType.GROUP_APPROVAL_RESULT, notification.getType());
        assertEquals(BusinessType.GROUP, notification.getBusinessType());
        assertEquals(GROUP_ID, notification.getBusinessId());
        assertEquals("小组审批结果", notification.getTitle());
        assertTrue(notification.getContent().contains("《算法小组》"));
        assertTrue(notification.getContent().contains("恭喜您成功加入"));
        assertTrue(notification.getExtraData().contains("\"toUserId\":7"));

        verify(webSocketPushService).pushToUserQueue(
                eq(TO_USER_ID), eq("INBOX_GROUP_APPROVAL_RESULT"), any(ApproveResult.class));
        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void barePayloadIsAlsoAccepted() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
        String body = jsonOf(buildDto());

        handler.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void duplicateCompletedEventIsAcknowledgedWithoutSideEffects() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(notificationCenterMapper, never()).insert(any(NotificationCenter.class));
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void duplicateInsertCompletesAndAcksWithoutPush() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.RECLAIM);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new DuplicateKeyException("uk_message_id 冲突"));
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void websocketPushFailureDoesNotBlockAck() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
        doThrow(new IllegalStateException("ws down"))
                .when(webSocketPushService).pushToUserQueue(any(), anyString(), any());
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(consumedEventService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void firstFailureRequeuesImmediately() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new IllegalStateException("db down"));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(1);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).recordFailure(eq(MESSAGE_ID), anyString());
        verify(consumedEventService, never()).complete(anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    @Test
    void thirdFailureMarksFailedAndDiscards() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new IllegalStateException("db down"));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(3);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).markFailed(eq(MESSAGE_ID), anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void missingRecipientIsDeadLetteredWithoutClaim() throws IOException {
        ApproveResult dto = buildDto();
        dto.setToUserId(null);
        String body = jsonOf(dto);

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(notificationCenterMapper);
    }

    @Test
    void oversizedMessageIdIsDeadLetteredWithoutClaim() throws IOException {
        ApproveResult dto = buildDto();
        dto.setMessageId("x".repeat(65));
        String body = jsonOf(dto);

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    private ApproveResult buildDto() {
        return ApproveResult.builder()
                .messageId(MESSAGE_ID)
                .groupId(GROUP_ID)
                .toUserId(TO_USER_ID)
                .groupName("算法小组")
                .context("恭喜您成功加入我们的小组")
                .sendTime("2026-11-08 20:00:00")
                .build();
    }

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap(
                EventTypes.GROUP_APPROVAL_RESULT, "service-quiz-nest", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private String jsonOf(ApproveResult dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Message amqpMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

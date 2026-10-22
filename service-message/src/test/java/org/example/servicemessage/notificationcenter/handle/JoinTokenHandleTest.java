package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.JoinToken;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组邀请码消费端测试：Outbox 信封/裸消息双读、consumed_event 幂等、
 * 收件箱字段与文案、WebSocket 尽力推送、失败重试/超限留存、毒消息丢弃。
 */
@ExtendWith(MockitoExtension.class)
class JoinTokenHandleTest {

    private static final long DELIVERY_TAG = 11L;
    private static final Long GROUP_ID = 66L;
    private static final Long TO_USER_ID = 7L;
    private static final String TOKEN = "012345";
    private static final String MESSAGE_ID = "quiz:join-token:" + GROUP_ID + ":" + TO_USER_ID + ":" + TOKEN;

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
    private JoinTokenHandle joinTokenHandle;

    @Test
    void successfulChainInsertsNotificationPushesCompletesAndAcks() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        InOrder inOrder = inOrder(consumedEventService, notificationCenterMapper, webSocketPushService);
        inOrder.verify(consumedEventService).claim(MESSAGE_ID, joinTokenHandle.getRoutingKey());
        inOrder.verify(notificationCenterMapper).insert(any(NotificationCenter.class));
        inOrder.verify(webSocketPushService).pushToUserQueue(
                eq(TO_USER_ID), eq("INBOX_GROUP_INVITE"), any(JoinToken.class));
        inOrder.verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);

        ArgumentCaptor<NotificationCenter> captor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper).insert(captor.capture());
        NotificationCenter notification = captor.getValue();
        assertEquals(MESSAGE_ID, notification.getMessageId());
        assertEquals(TO_USER_ID, notification.getUserId());
        assertEquals(NotificationCenterType.GROUP_INVITE, notification.getType());
        assertEquals(BusinessType.GROUP, notification.getBusinessType());
        assertEquals(GROUP_ID, notification.getBusinessId());
        assertEquals("小组邀请", notification.getTitle());
        assertTrue(notification.getContent().contains("《算法小组》"));
        assertTrue(notification.getContent().contains("邀请码：" + TOKEN));
        assertTrue(notification.getContent().contains("7 天内有效"));
        assertTrue(notification.getContent().contains("一起刷题"));
        assertTrue(notification.getExtraData().contains("\"token\":\"" + TOKEN + "\""));
        assertTrue(notification.getExtraData().contains("\"toUserId\":" + TO_USER_ID));

        ArgumentCaptor<JoinToken> pushCaptor = ArgumentCaptor.forClass(JoinToken.class);
        verify(webSocketPushService).pushToUserQueue(
                eq(TO_USER_ID), eq("INBOX_GROUP_INVITE"), pushCaptor.capture());
        assertEquals(TOKEN, pushCaptor.getValue().getToken());
        assertEquals(GROUP_ID, pushCaptor.getValue().getGroupId());
    }

    @Test
    void barePayloadIsAlsoAccepted() throws IOException {
        String body = jsonOf(buildDto());
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);

        joinTokenHandle.handle(body, channel, amqpMessage(body));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void duplicateCompletedEventIsAcknowledgedWithoutSideEffects() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(notificationCenterMapper, never()).insert(any(NotificationCenter.class));
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void duplicateInsertCompletesAndAcksWithoutPush() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.RECLAIM);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new DuplicateKeyException("uk_message_id 冲突"));

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void websocketPushFailureDoesNotBlockAck() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
        doThrow(new IllegalStateException("ws down"))
                .when(webSocketPushService).pushToUserQueue(any(), anyString(), any());

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(consumedEventService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void firstFailureRequeuesImmediately() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new IllegalStateException("db down"));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(1);

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).recordFailure(eq(MESSAGE_ID), anyString());
        verify(consumedEventService, never()).complete(anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    @Test
    void thirdFailureMarksFailedAndDiscards() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, joinTokenHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new IllegalStateException("db down"));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(3);

        joinTokenHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).markFailed(eq(MESSAGE_ID), anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void invalidJsonIsDeadLetteredWithoutClaim() throws IOException {
        String body = "not-json";

        joinTokenHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(notificationCenterMapper);
        verifyNoInteractions(webSocketPushService);
    }

    @Test
    void missingTokenIsDeadLetteredWithoutClaim() throws IOException {
        JoinToken dto = buildDto();
        dto.setToken(" ");
        String body = jsonOf(dto);

        joinTokenHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    @Test
    void oversizedMessageIdIsDeadLetteredWithoutClaim() throws IOException {
        JoinToken dto = buildDto();
        dto.setMessageId("x".repeat(65));
        String body = jsonOf(dto);

        joinTokenHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    private JoinToken buildDto() {
        return JoinToken.builder()
                .messageId(MESSAGE_ID)
                .token(TOKEN)
                .groupId(GROUP_ID)
                .toUserId(TO_USER_ID)
                .context("一起刷题")
                .groupName("算法小组")
                .userName("小明")
                .sendTime("2026-10-22 10:00:00")
                .build();
    }

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap(
                EventTypes.GROUP_JOIN_TOKEN, "service-quiz-nest", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private String jsonOf(JoinToken dto) {
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

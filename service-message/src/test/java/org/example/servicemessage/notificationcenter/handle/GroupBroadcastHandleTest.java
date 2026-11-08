package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.BroadCast;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组广播消费端测试：逐接收人写收件箱、messageId 后缀、部分重复跳过、
 * 幂等跳过、毒消息丢弃、失败重试/超限留存、WebSocket 尽力推送。
 */
@ExtendWith(MockitoExtension.class)
class GroupBroadcastHandleTest {

    private static final long DELIVERY_TAG = 21L;
    private static final Long GROUP_ID = 88L;
    private static final Long SENDER_ID = 1L;
    private static final String MESSAGE_ID = "00000000-0000-0000-0000-000000000001";
    private static final List<Long> USER_IDS = List.of(7L, 8L);

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
    private GroupBroadcastHandle handler;

    @Test
    void fanOutWritesOneNotificationPerRecipientAndPushesEach() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        ArgumentCaptor<NotificationCenter> captor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper, times(USER_IDS.size())).insert(captor.capture());
        List<NotificationCenter> notifications = captor.getAllValues();
        for (int i = 0; i < USER_IDS.size(); i++) {
            Long userId = USER_IDS.get(i);
            NotificationCenter notification = notifications.get(i);
            assertEquals(MESSAGE_ID + ":" + userId, notification.getMessageId());
            assertEquals(userId, notification.getUserId());
            assertEquals(NotificationCenterType.GROUP_BROADCAST, notification.getType());
            assertEquals(BusinessType.GROUP, notification.getBusinessType());
            assertEquals(GROUP_ID, notification.getBusinessId());
            assertEquals("小组广播", notification.getTitle());
            assertTrue(notification.getContent().contains("《算法小组》"));
            assertTrue(notification.getContent().contains("管理员：今晚八点刷题"));
            assertTrue(notification.getExtraData().contains("\"groupId\":88"));
            verify(webSocketPushService).pushToUserQueue(
                    eq(userId), eq("INBOX_GROUP_BROADCAST"), any(BroadCast.class));
        }
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
        verify(webSocketPushService, never()).pushToUserQueue(anyLong(), anyString(), any());
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void duplicateRecipientIsSkippedButOthersAreStillWritten() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.RECLAIM);
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new DuplicateKeyException("uk_message_id 冲突"))
                .thenReturn(1);
        String body = envelopeBody();

        handler.handle(body, channel, amqpMessage(body));

        verify(notificationCenterMapper, times(2)).insert(any(NotificationCenter.class));
        verify(webSocketPushService, times(1)).pushToUserQueue(
                eq(USER_IDS.get(1)), eq("INBOX_GROUP_BROADCAST"), any(BroadCast.class));
        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
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
    void emptyRecipientsIsDeadLetteredWithoutClaim() throws IOException {
        BroadCast dto = buildDto();
        dto.setUserIds(List.of());
        String body = jsonOf(dto);

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(notificationCenterMapper);
    }

    @Test
    void oversizedBroadcastIdIsDeadLetteredWithoutClaim() throws IOException {
        BroadCast dto = buildDto();
        dto.setMessageId("x".repeat(45));
        String body = jsonOf(dto);

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    @Test
    void invalidJsonIsDeadLetteredWithoutClaim() throws IOException {
        String body = "not-json";

        handler.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
    }

    private BroadCast buildDto() {
        return BroadCast.builder()
                .messageId(MESSAGE_ID)
                .groupId(GROUP_ID)
                .groupName("算法小组")
                .userIds(USER_IDS)
                .createUserId(SENDER_ID)
                .createUserName("管理员")
                .context("今晚八点刷题")
                .sendTime("2026-11-08 20:00:00")
                .build();
    }

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap(
                EventTypes.GROUP_BROADCAST, "service-quiz-nest", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private String jsonOf(BroadCast dto) {
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

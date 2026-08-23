package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicecommon.dto.ReviewMasteredDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.example.serviceapi.feign.QuestionFeignClient;
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
 * 复习掌握祝贺消费端测试：Outbox 信封消息双读解析、consumed_event 幂等（真重复跳过、
 * DuplicateKey 视为已完成）、Feign 拉取题目名失败重试/超限留存、毒消息丢弃。
 */
@ExtendWith(MockitoExtension.class)
class ReviewMasteredHandleTest {

    private static final long DELIVERY_TAG = 7L;
    private static final Long USER_ID = 7L;
    private static final Long QUESTION_ID = 101L;
    private static final String MESSAGE_ID = "review:mastered:" + USER_ID + ":" + QUESTION_ID;
    private static final String QUESTION_TITLE = "两数之和";

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private ConsumedEventService consumedEventService;
    @Mock
    private QuestionFeignClient questionFeignClient;
    @Mock
    private NotificationCenterMapper notificationCenterMapper;
    @Mock
    private WebSocketPushService webSocketPushService;
    @Mock
    private Channel channel;

    @InjectMocks
    private ReviewMasteredHandle reviewMasteredHandle;

    @Test
    void successfulChainClaimsFetchesInsertsPushesCompletesAndAcks() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(questionFeignClient.getQuestionInfo(QUESTION_ID))
                .thenReturn(Result.success(buildQuestionDto()));
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);

        reviewMasteredHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        InOrder inOrder = inOrder(consumedEventService, questionFeignClient,
                notificationCenterMapper, webSocketPushService);
        inOrder.verify(consumedEventService).claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey());
        inOrder.verify(questionFeignClient).getQuestionInfo(QUESTION_ID);
        inOrder.verify(notificationCenterMapper).insert(any(NotificationCenter.class));
        inOrder.verify(webSocketPushService).pushToUserQueue(
                eq(USER_ID), eq("INBOX_REVIEW"), any(ReviewMasteredDto.class));
        inOrder.verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);

        // 收件箱行字段：类型/业务关联/文案包含题目名、复习次数与加入日期，extraData 为补齐题目名后的 DTO
        ArgumentCaptor<NotificationCenter> notificationCaptor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper).insert(notificationCaptor.capture());
        NotificationCenter notification = notificationCaptor.getValue();
        assertEquals(MESSAGE_ID, notification.getMessageId());
        assertEquals(USER_ID, notification.getUserId());
        assertEquals(NotificationCenterType.REVIEW_MASTERED, notification.getType());
        assertEquals(BusinessType.REVIEW_RECORD, notification.getBusinessType());
        assertEquals(QUESTION_ID, notification.getBusinessId());
        assertEquals("复习掌握祝贺", notification.getTitle());
        assertTrue(notification.getContent().contains("《" + QUESTION_TITLE + "》"));
        assertTrue(notification.getContent().contains("6 次"));
        assertTrue(notification.getContent().contains("2026-01-15"));
        assertTrue(notification.getExtraData().contains("\"questionTitle\":\"" + QUESTION_TITLE + "\""));

        // WebSocket 推送载荷已补齐题目名
        ArgumentCaptor<ReviewMasteredDto> pushCaptor = ArgumentCaptor.forClass(ReviewMasteredDto.class);
        verify(webSocketPushService).pushToUserQueue(eq(USER_ID), eq("INBOX_REVIEW"), pushCaptor.capture());
        assertEquals(QUESTION_TITLE, pushCaptor.getValue().getQuestionTitle());
    }

    @Test
    void duplicateCompletedEventIsAcknowledgedWithoutSideEffects() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);

        reviewMasteredHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoInteractions(questionFeignClient);
        verify(notificationCenterMapper, never()).insert(any(NotificationCenter.class));
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void feignFailureRequeuesForRetry() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(questionFeignClient.getQuestionInfo(QUESTION_ID))
                .thenReturn(Result.error("题目服务不可用", 500));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(1);

        reviewMasteredHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).recordFailure(eq(MESSAGE_ID), anyString());
        verify(consumedEventService, never()).complete(anyString());
        verify(notificationCenterMapper, never()).insert(any(NotificationCenter.class));
        verify(channel).basicNack(DELIVERY_TAG, false, true);
    }

    @Test
    void thirdFeignFailureMarksFailedAndDiscards() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(questionFeignClient.getQuestionInfo(QUESTION_ID))
                .thenReturn(Result.error("题目服务不可用", 500));
        when(consumedEventService.recordFailure(eq(MESSAGE_ID), anyString())).thenReturn(3);

        reviewMasteredHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).markFailed(eq(MESSAGE_ID), anyString());
        verify(consumedEventService, never()).complete(anyString());
        verify(channel).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void invalidJsonIsDeadLetteredWithoutClaim() throws IOException {
        String body = "not-json";

        reviewMasteredHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(questionFeignClient);
        verifyNoInteractions(notificationCenterMapper);
        verifyNoInteractions(webSocketPushService);
    }

    @Test
    void missingMessageIdIsDeadLetteredWithoutClaim() throws IOException {
        ReviewMasteredDto dto = buildDto();
        dto.setMessageId(null);
        String body = jsonOf(dto);

        reviewMasteredHandle.handle(body, channel, amqpMessage(body));

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    void duplicateInsertCompletesAndAcks() throws IOException {
        when(consumedEventService.claim(MESSAGE_ID, reviewMasteredHandle.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(questionFeignClient.getQuestionInfo(QUESTION_ID))
                .thenReturn(Result.success(buildQuestionDto()));
        when(notificationCenterMapper.insert(any(NotificationCenter.class)))
                .thenThrow(new DuplicateKeyException("uk_message_id 冲突"));

        reviewMasteredHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        verify(consumedEventService).complete(MESSAGE_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
        // 收件箱已存在，不再推送，也不走失败重试
        verify(webSocketPushService, never()).pushToUserQueue(any(), anyString(), any());
        verify(consumedEventService, never()).recordFailure(anyString(), anyString());
    }

    private ReviewMasteredDto buildDto() {
        return ReviewMasteredDto.builder()
                .messageId(MESSAGE_ID)
                .userId(USER_ID)
                .questionId(QUESTION_ID)
                .masteredTime("2026-08-23 10:00:00")
                .joinTime("2026-01-15 09:30:00")
                .totalReviewCount(6)
                .build();
    }

    private QuestionDto buildQuestionDto() {
        return QuestionDto.builder()
                .questionId(QUESTION_ID)
                .title(QUESTION_TITLE)
                .build();
    }

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap(
                EventTypes.REVIEW_MASTERED, "service-review", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private String jsonOf(ReviewMasteredDto dto) {
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

package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.ReminderType;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复习提醒消费双读测试：Outbox 投递的统一信封消息（payload 为 NotificationDto）
 * 与旧格式裸 DTO JSON 均可解析入库；Redis 幂等命中时直接 ACK。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewHandleTest {

    private static final long DELIVERY_TAG = 7L;
    private static final Long USER_ID = 7L;
    private static final String MESSAGE_ID = "review-reminder:not_record:" + USER_ID + ":2026-08-23";
    private static final String IDEMPOTENT_KEY = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + MESSAGE_ID;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private NotificationCenterMapper notificationCenterMapper;
    @Mock
    private WebSocketPushService webSocketPushService;
    @Mock
    private Channel channel;

    @InjectMocks
    private ReviewHandle reviewHandle;

    @BeforeEach
    void stubCommonFlow() {
        when(redisTemplate.hasKey(IDEMPOTENT_KEY)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(notificationCenterMapper.insert(any(NotificationCenter.class))).thenReturn(1);
    }

    @Test
    void envelopeWrappedReminderIsUnwrappedAndPersisted() throws IOException {
        reviewHandle.handle(envelopeBody(), channel, amqpMessage(envelopeBody()));

        ArgumentCaptor<NotificationCenter> captor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper).insert(captor.capture());
        assertEquals(MESSAGE_ID, captor.getValue().getMessageId());
        assertEquals(USER_ID, captor.getValue().getUserId());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void bareReminderStillParsedAndPersisted() throws IOException {
        reviewHandle.handle(bareBody(), channel, amqpMessage(bareBody()));

        ArgumentCaptor<NotificationCenter> captor = ArgumentCaptor.forClass(NotificationCenter.class);
        verify(notificationCenterMapper).insert(captor.capture());
        assertEquals(MESSAGE_ID, captor.getValue().getMessageId());
        assertEquals("今日复习计划待创建", captor.getValue().getTitle());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void redisIdempotentHitAcknowledgesWithoutInsert() throws IOException {
        when(redisTemplate.hasKey(IDEMPOTENT_KEY)).thenReturn(true);

        reviewHandle.handle(bareBody(), channel, amqpMessage(bareBody()));

        verify(notificationCenterMapper, never()).insert(any(NotificationCenter.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private String bareBody() {
        return jsonOf(buildDto());
    }

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap(
                EventTypes.REVIEW_REMINDER, "service-review", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private NotificationDto buildDto() {
        NotificationDto dto = new NotificationDto();
        dto.setMessageId(MESSAGE_ID);
        dto.setType(NotificationCenterType.REVIEW);
        dto.setBusinessType(BusinessType.REVIEW_RECORD);
        dto.setUserId(USER_ID);
        dto.setExtraData("{\"reminderType\":\"NOTRECORD\",\"total\":3}");
        return dto;
    }

    private String jsonOf(NotificationDto dto) {
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

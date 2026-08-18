package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.notification.NotificationAppealDto;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicemessage.mq.MessageHandler;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 申诉通知处理器
 */
@Component
@Slf4j
public class AppealHandle implements MessageHandler {
    private static final int MAX_RETRY_COUNT = 3;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationCenterMapper notificationCenterMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private WebSocketPushService webSocketPushService;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_APPEAL_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        log.debug("=================================handle appeal message: {}", message);
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        NotificationDto notificationDto;
        NotificationAppealDto appealDto;
        
        try {
            notificationDto = objectMapper.readValue(message, NotificationDto.class);
            validate(notificationDto);
            appealDto = objectMapper.readValue(notificationDto.getExtraData(), NotificationAppealDto.class);
        } catch (Exception e) {
            log.error("申诉通知消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String idempotentKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + notificationDto.getMessageId();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(idempotentKey))) {
            log.info("申诉通知重复消费，messageId={}", notificationDto.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            NotificationCenter notification = new NotificationCenter(notificationDto);
            notificationCenterMapper.insert(notification);

            markSuccess(notificationDto.getMessageId(), idempotentKey);
            
            try {
                webSocketPushService.pushToUserQueue(
                        notification.getUserId(), 
                        WebSocketQueueName.INBOX_APPEAL.name(), 
                        notification
                );
            } catch (Exception pushException) {
                log.warn("申诉通知已入库，但 WebSocket 推送失败，messageId={}",
                        notificationDto.getMessageId(), pushException);
            }
            
            channel.basicAck(deliveryTag, false);
            
        } catch (DuplicateKeyException duplicateKeyException) {
            markSuccess(notificationDto.getMessageId(), idempotentKey);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleFailure(notificationDto, deliveryTag, channel, e);
        }
    }

    private void validate(NotificationDto dto) {
        if (dto == null || dto.getMessageId() == null || dto.getMessageId().isBlank()
                || dto.getUserId() == null
                || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("申诉通知缺少必要字段");
        }
    }

    private void markSuccess(String messageId, String idempotentKey) {
        try {
            redisTemplate.opsForValue().set(idempotentKey, "success", 7, TimeUnit.DAYS);
            redisTemplate.opsForHash().delete(RedisContext.MQ_APPEAL_RETRY_COUNT_KEY, messageId);
        } catch (Exception e) {
            log.warn("申诉通知已入库，但 Redis 幂等缓存写入失败，messageId={}", messageId, e);
        }
    }

    private void handleFailure(NotificationDto dto, long deliveryTag, Channel channel, Exception exception)
            throws IOException {
        Long retryCount = redisTemplate.opsForHash().increment(
                RedisContext.MQ_APPEAL_RETRY_COUNT_KEY, dto.getMessageId(), 1
        );
        boolean exhausted = retryCount != null && retryCount >= MAX_RETRY_COUNT;
        log.error("申诉通知处理失败，messageId={}，第次失败", dto.getMessageId(), retryCount, exception);
        
        if (exhausted) {
            redisTemplate.opsForHash().put(RedisContext.MQ_APPEAL_FAILED_KEY, dto.getMessageId(), dto);
            redisTemplate.opsForValue().set(
                    RedisContext.NOTIFICATION_IDEMPOTENT_KEY + dto.getMessageId(), "failed", 7, TimeUnit.DAYS
            );
        }
        channel.basicNack(deliveryTag, false, !exhausted);
    }
}

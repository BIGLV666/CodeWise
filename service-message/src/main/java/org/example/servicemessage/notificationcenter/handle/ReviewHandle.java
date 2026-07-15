package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.mqMessages.NotificationDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.serviceapi.dto.NotificationReviewMqDto;
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

@Component
@Slf4j
public class ReviewHandle implements MessageHandler {
    private static final int MAX_RETRY_COUNT = 3;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private NotificationCenterMapper notificationCenterMapper;
    @Autowired
    private WebSocketPushService webSocketPushService;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        NotificationDto notificationDto;
        NotificationReviewMqDto reviewDto;
        try {
            notificationDto = objectMapper.readValue(message, NotificationDto.class);
            validate(notificationDto);
            reviewDto = objectMapper.readValue(notificationDto.getExtraData(), NotificationReviewMqDto.class);
            validate(reviewDto);
        } catch (Exception e) {
            log.error("复习通知消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String idempotentKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + notificationDto.getMessageId();
        if (redisTemplate.hasKey(idempotentKey)) {
            log.info("复习通知重复消费，messageId={}", notificationDto.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            NotificationCenter notification = new NotificationCenter(notificationDto);
            switch (reviewDto.getReminderType()) {
                case NOTRECORD -> handleNotRecord(notification, reviewDto);
                case HAVERECORD -> handleHaveRecord(notification, reviewDto);
            }
            notificationCenterMapper.insert(notification);

            markSuccess(notificationDto.getMessageId(), idempotentKey);
            try {
                webSocketPushService.pushToUserQueue(
                        notification.getUserId(), WebSocketQueueName.INBOX_REVIEW.name(), notification
                );
            } catch (Exception pushException) {
                log.warn("复习通知已入库，但 WebSocket 推送失败，messageId={}",
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
                || dto.getUserId() == null || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("复习通知缺少必要字段");
        }
    }

    private void validate(NotificationReviewMqDto dto) {
        if (dto == null || dto.getReminderType() == null || dto.getTotal() == null || dto.getTotal() < 0) {
            throw new IllegalArgumentException("复习通知扩展数据不完整");
        }
    }

    private void markSuccess(String messageId, String idempotentKey) {
        try {
            redisTemplate.opsForValue().set(idempotentKey, "success", 7, TimeUnit.DAYS);
            redisTemplate.opsForHash().delete(RedisContext.MQ_REVIEW_RETRY_COUNT_KEY, messageId);
        } catch (Exception e) {
            log.warn("复习通知已入库，但 Redis 幂等缓存写入失败，messageId={}", messageId, e);
        }
    }

    private void handleFailure(NotificationDto dto, long deliveryTag, Channel channel, Exception exception)
            throws IOException {
        Long retryCount = redisTemplate.opsForHash().increment(
                RedisContext.MQ_REVIEW_RETRY_COUNT_KEY, dto.getMessageId(), 1
        );
        boolean exhausted = retryCount != null && retryCount >= MAX_RETRY_COUNT;
        log.error("复习通知处理失败，messageId={}，第{}次失败", dto.getMessageId(), retryCount, exception);
        if (exhausted) {
            redisTemplate.opsForHash().put(RedisContext.MQ_REVIEW_FAILED_KEY, dto.getMessageId(), dto);
            redisTemplate.opsForValue().set(
                    RedisContext.NOTIFICATION_IDEMPOTENT_KEY + dto.getMessageId(), "failed", 7, TimeUnit.DAYS
            );
        }
        channel.basicNack(deliveryTag, false, !exhausted);
    }


    /** 提醒尚未创建今日复习记录的用户。 */
    private void handleNotRecord(NotificationCenter notification, NotificationReviewMqDto dto) {
        long total = dto.getTotal() == null ? 0 : dto.getTotal();
        notification.setTitle("今日复习计划待创建");
        notification.setContent("今天有" + total + "道题目需要复习，你还没有生成今日复习计划，记得及时开始复习。");
    }

    /** 提醒今日复习记录仍有待完成题目的用户。 */
    private void handleHaveRecord(NotificationCenter notification, NotificationReviewMqDto dto) {
        long total = dto.getTotal() == null ? 0 : dto.getTotal();
        notification.setTitle("今日复习计划尚未完成");
        notification.setContent("你的今日复习计划还有" + total + "道题目未完成，记得在今天结束前完成复习。");
    }

}

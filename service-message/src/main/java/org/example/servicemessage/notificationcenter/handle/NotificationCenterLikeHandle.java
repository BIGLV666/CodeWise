package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.notification.NotificationLikeDto;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.dto.notification.NotificationDto;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class NotificationCenterLikeHandle implements MessageHandler {
    private static final int MAX_RETRY_COUNT = 3;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationCenterMapper notificationCenterMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private WebSocketPushService webSocketPushService;
    private final Integer maxRetryTime=10;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_LIKE_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        NotificationDto notificationDto;
        try {
            notificationDto = objectMapper.readValue(message, NotificationDto.class);
            validate(notificationDto);
        } catch (Exception e) {
            log.error("点赞通知消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String idempotentKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + notificationDto.getMessageId();
        if (redisTemplate.hasKey(idempotentKey)) {
            log.info("点赞通知重复消费，messageId={}", notificationDto.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            NotificationLikeDto actor = objectMapper.readValue(notificationDto.getExtraData(), NotificationLikeDto.class);
            String actorName = actor.getNickName() == null ? "用户" : actor.getNickName();
            String businessName = Type.valueOf(notificationDto.getBusinessType().name()).getName();

            NotificationCenter notification = new NotificationCenter(notificationDto);
            notification.setTitle(actorName + "赞了你的" + businessName);
            notification.setContent("你的" + businessName + "收到了新的点赞，点击查看详情。");
            notificationCenterMapper.insert(notification);

            markSuccess(notificationDto.getMessageId(), idempotentKey);
            // 点赞通知当前只写入站内收件箱，实时 WebSocket 推送暂不启用。
            // 前端通过通知中心接口读取 extraData 后，结合 businessType/businessId
            // 与 rootType/rootId/rootCommentId/questionId 完成页面跳转。
//            try {
//                webSocketPushService.pushToUserQueue(
//                        notification.getUserId(), WebSocketQueueName.INBOX_LIKE.name(), notification
//                );
//            } catch (Exception pushException) {
//                // 通知已经持久化，实时推送失败不应造成数据库通知重复。
//                log.warn("点赞通知已入库，但 WebSocket 推送失败，messageId={}",
//                        notificationDto.getMessageId(), pushException);
//            }
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
                || dto.getUserId() == null || dto.getBusinessType() == null
                || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("点赞通知缺少必要字段");
        }
    }

    private void markSuccess(String messageId, String idempotentKey) {
        try {
            redisTemplate.opsForValue().set(idempotentKey, "success", 7, TimeUnit.DAYS);
            redisTemplate.opsForHash().delete(RedisContext.MQ_LIKE_RETRY_COUNT_KEY, messageId);
        } catch (Exception e) {
            log.warn("点赞通知已入库，但 Redis 幂等缓存写入失败，messageId={}", messageId, e);
        }
    }

    private void handleFailure(NotificationDto dto, long deliveryTag, Channel channel, Exception exception)
            throws IOException {
        Long retryCount = redisTemplate.opsForHash().increment(
                RedisContext.MQ_LIKE_RETRY_COUNT_KEY, dto.getMessageId(), 1
        );
        boolean exhausted = retryCount != null && retryCount >= MAX_RETRY_COUNT;
        log.error("点赞通知处理失败，messageId={}，第{}次失败", dto.getMessageId(), retryCount, exception);
        if (exhausted) {
            redisTemplate.opsForHash().put(RedisContext.MQ_LIKE_FAILED_KEY, dto.getMessageId(), dto);
            redisTemplate.opsForValue().set(
                    RedisContext.NOTIFICATION_IDEMPOTENT_KEY + dto.getMessageId(), "failed", 7, TimeUnit.DAYS
            );
        }
        channel.basicNack(deliveryTag, false, !exhausted);
    }


    @Getter
    private enum Type {
        POST("帖子"),
        COMMENT("评论"),
        SOLUTION("题解");

        private final String name;

        Type(String name) {
            this.name = name;
        }
    }
}

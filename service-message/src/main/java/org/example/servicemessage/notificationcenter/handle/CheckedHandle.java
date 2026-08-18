package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.notification.NotificationCheckedDto;
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
 * 内容审核结果通知处理器。
 * 先把审核结论写入收件箱，再尽力做一次 WebSocket 推送；推送失败不影响已入库的通知。
 */
@Component
@Slf4j
public class CheckedHandle implements MessageHandler {
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
        return MqContexts.NOTIFICATION_CHECKED_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        log.debug("=================================handle message: {}", message);
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        NotificationDto notificationDto;
        NotificationCheckedDto checkedDto;
        try {
            notificationDto = objectMapper.readValue(message, NotificationDto.class);
            validate(notificationDto);
            checkedDto = objectMapper.readValue(notificationDto.getExtraData(), NotificationCheckedDto.class);
            validate(checkedDto);
        } catch (Exception e) {
            log.error("审核通知消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String idempotentKey = RedisContext.NOTIFICATION_IDEMPOTENT_KEY + notificationDto.getMessageId();
        if (redisTemplate.hasKey(idempotentKey)) {
            log.info("审核通知重复消费，messageId={}", notificationDto.getMessageId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            NotificationCenter notification = new NotificationCenter(notificationDto);
            fillContent(notification, checkedDto);
            notificationCenterMapper.insert(notification);

            markSuccess(notificationDto.getMessageId(), idempotentKey);
            try {
                webSocketPushService.pushToUserQueue(
                        notification.getUserId(), WebSocketQueueName.INBOX_CHECKED.name(), notification
                );
            } catch (Exception pushException) {
                log.warn("审核通知已入库，但 WebSocket 推送失败，messageId={}",
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

    /** 按审核结论拼装通知标题和正文，发送方已给出正文时优先使用。 */
    private void fillContent(NotificationCenter notification, NotificationCheckedDto dto) {
        String businessName = Type.valueOf(notification.getBusinessType().name()).getName();
        String title = dto.getTitle() == null || dto.getTitle().isBlank() ? "" : "《" + dto.getTitle() + "》";
        if (Boolean.TRUE.equals(dto.getTakeDown())) {
            notification.setTitle("你的" + businessName + "已被下架");
            notification.setContent(contentOrDefault(dto,
                    "你的" + businessName + title + "因不符合社区规范已被管理员下架，如有疑问可联系管理员申诉。"));
            return;
        }
        if (Boolean.TRUE.equals(dto.getPassed())) {
            notification.setTitle("你的" + businessName + "已通过审核");
            notification.setContent(contentOrDefault(dto,
                    "你的" + businessName + title + "已通过审核，现在其他用户可以看到它了。"));
            return;
        }
        notification.setTitle("你的" + businessName + "未通过审核");
        notification.setContent(contentOrDefault(dto,
                "你的" + businessName + title + "未通过审核，请检查内容是否符合社区规范后重新发布。"));
    }

    private String contentOrDefault(NotificationCheckedDto dto, String defaultContent) {
        return dto.getMessage() == null || dto.getMessage().isBlank() ? defaultContent : dto.getMessage();
    }

    private void validate(NotificationDto dto) {
        if (dto == null || dto.getMessageId() == null || dto.getMessageId().isBlank()
                || dto.getUserId() == null || dto.getBusinessType() == null
                || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("审核通知缺少必要字段");
        }
    }

    private void validate(NotificationCheckedDto dto) {
        if (dto == null || dto.getPassed() == null) {
            throw new IllegalArgumentException("审核通知扩展数据不完整");
        }
    }

    private void markSuccess(String messageId, String idempotentKey) {
        try {
            redisTemplate.opsForValue().set(idempotentKey, "success", 7, TimeUnit.DAYS);
            redisTemplate.opsForHash().delete(RedisContext.MQ_CHECKED_RETRY_COUNT_KEY, messageId);
        } catch (Exception e) {
            log.warn("审核通知已入库，但 Redis 幂等缓存写入失败，messageId={}", messageId, e);
        }
    }

    private void handleFailure(NotificationDto dto, long deliveryTag, Channel channel, Exception exception)
            throws IOException {
        Long retryCount = redisTemplate.opsForHash().increment(
                RedisContext.MQ_CHECKED_RETRY_COUNT_KEY, dto.getMessageId(), 1
        );
        boolean exhausted = retryCount != null && retryCount >= MAX_RETRY_COUNT;
        log.error("审核通知处理失败，messageId={}，第{}次失败", dto.getMessageId(), retryCount, exception);
        if (exhausted) {
            redisTemplate.opsForHash().put(RedisContext.MQ_CHECKED_FAILED_KEY, dto.getMessageId(), dto);
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

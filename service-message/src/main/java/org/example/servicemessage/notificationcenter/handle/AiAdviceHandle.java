package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.NotificationAiAdviceDto;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicemessage.mq.MessageHandler;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class AiAdviceHandle implements MessageHandler {
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private WebSocketPushService webSocketPushService;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        NotificationDto notificationDto;
        NotificationAiAdviceDto adviceDto;

        try {
            notificationDto = objectMapper.readValue(message, NotificationDto.class);
            validate(notificationDto);
            adviceDto = objectMapper.readValue(notificationDto.getExtraData(), NotificationAiAdviceDto.class);
        } catch (Exception e) {
            log.error("AI advice message is invalid: {}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String idempotentKey = RedisContext.AI_ADVICE_PUSH_IDEMPOTENT_KEY + notificationDto.getMessageId();
        try {
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    idempotentKey, "pushed", 1, TimeUnit.DAYS))) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            webSocketPushService.pushToUserQueue(
                    notificationDto.getUserId(), WebSocketQueueName.AI_ADVICE.name(), adviceDto
            );
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("AI advice push failed, messageId={}", notificationDto.getMessageId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void validate(NotificationDto dto) {
        if (dto == null || dto.getMessageId() == null || dto.getMessageId().isBlank()
                || dto.getUserId() == null || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("AI advice message is missing required fields");
        }
    }
}

package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.NotificationAiAdviceDto;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.servicecommon.config.MqContexts;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.mq.MessageHandler;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * AI 学情建议推送处理器（notification.ai.advice.routing，手动 ACK）。
 *
 * <p>以 messageId 作为 eventId 走 consumed_event 数据库幂等：业务执行前先 claim 占位
 * （PROCESSING），推送成功后置 COMPLETED 再 ACK。COMPLETED 是唯一跳过态；
 * PROCESSING/FAILED 在消息重投时重新执行推送（at-least-once，崩溃恢复后业务可重跑）。
 * 推送失败按 retry_count 计数：不足 3 次 nack 重投立即重试；达到 3 次置 FAILED 留存
 * （人工重放 = 将 consumed_event 该行 status 改回 PROCESSING 后由生产方重发或人工
 * 推送）后 nack 丢弃。</p>
 */
@Component
@Slf4j
public class AiAdviceHandle implements MessageHandler {
    /** 最大立即重试次数，达到后失败留存并丢弃。 */
    private static final int MAX_RETRY_COUNT = 3;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private WebSocketPushService webSocketPushService;
    @Autowired
    private ConsumedEventService consumedEventService;

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

        String messageId = notificationDto.getMessageId();
        if (consumedEventService.claim(messageId, getRoutingKey())
                == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("AI advice message already consumed, skip push: messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            webSocketPushService.pushToUserQueue(
                    notificationDto.getUserId(), WebSocketQueueName.AI_ADVICE.name(), adviceDto
            );
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleFailure(messageId, e, deliveryTag, channel);
        }
    }

    /**
     * 推送失败后的统一处置：不足 {@value MAX_RETRY_COUNT} 次 nack 重投立即重试；
     * 达到后置 FAILED 留存并 nack 丢弃。
     */
    private void handleFailure(String messageId, Exception e, long deliveryTag, Channel channel)
            throws IOException {
        int retryCount = consumedEventService.recordFailure(messageId, e.toString());
        if (retryCount < MAX_RETRY_COUNT) {
            log.warn("AI advice push failed, requeue for retry: messageId={}, retryCount={}",
                    messageId, retryCount, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        consumedEventService.markFailed(messageId, e.toString());
        log.error("AI advice push failed {} times, mark FAILED for manual replay and drop: messageId={}",
                MAX_RETRY_COUNT, messageId, e);
        channel.basicNack(deliveryTag, false, false);
    }

    private void validate(NotificationDto dto) {
        if (dto == null || dto.getMessageId() == null || dto.getMessageId().isBlank()
                || dto.getUserId() == null || dto.getExtraData() == null || dto.getExtraData().isBlank()) {
            throw new IllegalArgumentException("AI advice message is missing required fields");
        }
    }
}

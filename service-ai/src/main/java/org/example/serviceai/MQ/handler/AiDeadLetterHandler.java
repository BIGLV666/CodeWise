package org.example.serviceai.MQ.handler;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.service.ConsumedEventService;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 死信处理器（ai.dead.queue，手动 ACK）。
 *
 * <p>死信来源：毒消息（载荷解析失败）与延迟重试超限的业务消息。尽力提取
 * 信封 eventId：提取到则把 consumed_event 行标记为终态 FAILED
 * （仅当状态非 COMPLETED 时更新，避免覆盖已完成事件）并记录死信 body
 * 留存排查；提取不到（如 testcase 裸格式消息）只记录日志。标记失败
 * 不重投（死信队列无下游），原始 body 已在日志留痕。</p>
 */
@Slf4j
@Component
public class AiDeadLetterHandler {

    @Autowired
    private ConsumedEventService consumedEventService;

    /**
     * 消费一条 AI 死信消息。
     *
     * @param message 原始 AMQP 消息（body 为信封或裸格式）
     * @param channel 当前 channel，用于手动 ACK
     * @throws IOException ACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = MqContexts.AI_DLQ)
    public void consumeDeadMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        String eventId = resolveEventId(body);
        log.error("AI 消息进入死信队列: eventId={}, death={}, body={}",
                eventId, message.getMessageProperties().getHeader("x-death"), abbreviate(body));

        if (eventId != null) {
            try {
                consumedEventService.markFailedIfNotCompleted(eventId, "dead-letter after retries");
            } catch (Exception exception) {
                // 标记失败不重投（无下游可去），原始 body 已留痕
                log.error("死信事件终态标记失败: eventId={}", eventId, exception);
            }
        }
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 尽力提取死信消息的信封 eventId；非信封格式或解析失败返回 null（仅留痕）。
     */
    private String resolveEventId(String body) {
        if (!EnvelopeCodec.isEnvelope(body)) {
            return null;
        }
        try {
            return EnvelopeCodec.readEnvelope(body).getEventId();
        } catch (IllegalArgumentException exception) {
            log.warn("死信信封解析失败，仅留痕: body={}", abbreviate(body), exception);
            return null;
        }
    }

    /** 日志用的消息体截断，避免超长载荷刷屏。 */
    private String abbreviate(String body) {
        if (body == null || body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200) + "...";
    }
}

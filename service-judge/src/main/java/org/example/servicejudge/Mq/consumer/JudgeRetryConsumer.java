package org.example.servicejudge.Mq.consumer;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicejudge.Mq.handler.JudgeRetryHandler;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 失败重试队列消费者（judge.retry.queue，手动 ACK）。
 *
 * <p>业务异常时 {@link JudgeRetryHandler} 已将 failure_submit 落库为 FAILURE，
 * 本消费者直接 nack(requeue=false) 进死信仅留痕，不做延迟重试；
 * 载荷解析失败（毒消息）同样直接死信。</p>
 */
@Component
@Slf4j
public class JudgeRetryConsumer {

    private final JudgeRetryHandler judgeRetryHandler;

    public JudgeRetryConsumer(JudgeRetryHandler judgeRetryHandler) {
        this.judgeRetryHandler = judgeRetryHandler;
    }

    /**
     * 消费一条失败重试消息。
     *
     * @param message 原始 AMQP 消息（body 为信封或裸 failureId JSON）
     * @param channel 当前 channel，用于手动 ACK/NACK
     * @throws IOException ACK/NACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = MqContexts.JUDGE_RETRY_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        logEnvelopeIfPresent(body);

        final Long failureId;
        try {
            // 信封/裸格式双读：毒消息在此暴露为 IllegalArgumentException
            failureId = EnvelopeCodec.unwrap(body, Long.class);
        } catch (IllegalArgumentException exception) {
            log.error("重试消息载荷解析失败，按毒消息直接死信: body={}", body, exception);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            judgeRetryHandler.handle(failureId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            // handler 已记录 failure_submit=FAILURE，这里死信仅留痕
            log.error("失败重试处理失败（状态已落库），消息转死信留痕: failureId={}", failureId, exception);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 信封格式消息记录 eventId/eventType，便于控制台与日志检索。 */
    private void logEnvelopeIfPresent(String body) {
        if (!EnvelopeCodec.isEnvelope(body)) {
            return;
        }
        try {
            EventEnvelope envelope = EnvelopeCodec.readEnvelope(body);
            log.info("收到判题重试事件: eventId={}, eventType={}, traceId={}",
                    envelope.getEventId(), envelope.getEventType(), envelope.getTraceId());
        } catch (IllegalArgumentException exception) {
            log.warn("信封解析失败，按裸格式继续处理", exception);
        }
    }
}

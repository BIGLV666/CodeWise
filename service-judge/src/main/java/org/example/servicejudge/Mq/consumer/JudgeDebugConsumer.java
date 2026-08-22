package org.example.servicejudge.Mq.consumer;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicejudge.Mq.handler.JudgeDebugHandler;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 调试判题队列消费者（judge.debug.queue，手动 ACK）。
 *
 * <p>调试链路不进入重试：业务失败由 {@link JudgeDebugHandler} 内部消化为
 * SYSTEM_ERROR 结果写回 Redis 并回调题目服务后正常返回（ACK）；仅当载荷
 * 解析失败（毒消息）或错误处理本身抛出异常时才 nack 进死信留痕。</p>
 */
@Component
@Slf4j
public class JudgeDebugConsumer {

    private final JudgeDebugHandler judgeDebugHandler;

    public JudgeDebugConsumer(JudgeDebugHandler judgeDebugHandler) {
        this.judgeDebugHandler = judgeDebugHandler;
    }

    /**
     * 消费一条调试判题消息。
     *
     * @param message 原始 AMQP 消息（body 为信封或裸 uuid 字符串）
     * @param channel 当前 channel，用于手动 ACK/NACK
     * @throws IOException ACK/NACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = MqContexts.JUDGE_DEBUG_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        logEnvelopeIfPresent(body);

        final String uuid;
        try {
            // 信封/裸格式双读：毒消息在此暴露为 IllegalArgumentException
            uuid = EnvelopeCodec.unwrap(body, String.class);
        } catch (IllegalArgumentException exception) {
            log.error("调试消息载荷解析失败，按毒消息直接死信: body={}", body, exception);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            judgeDebugHandler.handle(uuid);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            // 正常业务失败已在 handler 内写 Redis 错误结果；到这里说明错误处理本身失败
            log.error("调试任务错误处理失败，转死信留痕: uuid={}", uuid, exception);
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
            log.info("收到调试判题事件: eventId={}, eventType={}, traceId={}",
                    envelope.getEventId(), envelope.getEventType(), envelope.getTraceId());
        } catch (IllegalArgumentException exception) {
            log.warn("信封解析失败，按裸格式继续处理", exception);
        }
    }
}

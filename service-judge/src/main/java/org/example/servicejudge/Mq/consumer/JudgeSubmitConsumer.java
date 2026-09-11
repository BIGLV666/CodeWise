package org.example.servicejudge.Mq.consumer;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicejudge.Mq.handler.JudgeSubmitHandler;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 判题提交队列消费者（judge.submit.queue，手动 ACK）。
 *
 * <p>失败策略：载荷解析失败（毒消息）直接 nack 进 DLQ；业务异常按
 * {@code x-codewise-retry-count} 头指数退避（5s/10s/20s）——小于 3 次时把
 * 原始消息体（保持信封/裸格式不变）经默认交换机投入 judge.wait.queue，
 * TTL 到期后弹回本队列；达到 3 次仍失败则 nack 死信，由
 * {@code JudgeDeadLetterHandler} 登记 failure_submit。</p>
 */
@Component
@Slf4j
public class JudgeSubmitConsumer {

    /** 最大延迟重试次数，达到后进入死信队列。 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 退避基数（毫秒）：第 n 次重试延迟 5000 * 2^n。 */
    private static final long BASE_DELAY_MS = 5_000L;

    private final JudgeSubmitHandler judgeSubmitHandler;
    private final RabbitTemplate rabbitTemplate;

    public JudgeSubmitConsumer(JudgeSubmitHandler judgeSubmitHandler, RabbitTemplate rabbitTemplate) {
        this.judgeSubmitHandler = judgeSubmitHandler;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 消费一条提交判题消息。
     *
     * <p>并发度经 {@code codewise.judge.consumer-concurrency} 配置（默认 2）：判题吞吐上限 =
     * 消费并发 × 单题判题速率，必须与容器池容量（{@code /api/judge/containers} 扩容）同步上调——
     * 消费并发超过池容量只会在池等待队列空转，池容量超过消费并发则容器闲置（2026-09-12 压测实测：
     * 默认并发 1 时 2 容器池的第二个容器全程闲置）。</p>
     *
     * @param message 原始 AMQP 消息（body 为信封或裸 Long JSON）
     * @param channel 当前 channel，用于手动 ACK/NACK
     * @throws IOException ACK/NACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = MqContexts.JUDGE_SUBMIT_QUEUE, concurrency = "${codewise.judge.consumer-concurrency:2}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        logEnvelopeIfPresent(body);

        final Long submitRecordId;
        try {
            // 信封/裸格式双读：毒消息在此暴露为 IllegalArgumentException
            submitRecordId = EnvelopeCodec.unwrap(body, Long.class);
        } catch (IllegalArgumentException exception) {
            log.error("提交消息载荷解析失败，按毒消息直接死信: body={}", abbreviate(body), exception);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            judgeSubmitHandler.handle(submitRecordId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("提交判题处理失败，进入延迟重试或死信: submitId={}", submitRecordId, exception);
            scheduleRetryOrDeadLetter(body, message.getMessageProperties(), channel, deliveryTag);
        }
    }

    /**
     * 业务失败后的统一处置：未超限则原样转投等待队列做延迟重试，超限则死信。
     *
     * @param body            原始消息体字符串（保持信封或裸格式不变）
     * @param originalProps   原消息属性（携带 retryCount 头）
     * @param channel         当前 channel
     * @param deliveryTag     当前消息 delivery tag
     * @throws IOException    ACK/NACK 失败
     */
    private void scheduleRetryOrDeadLetter(
            String body,
            MessageProperties originalProps,
            Channel channel,
            long deliveryTag
    ) throws IOException {
        int retryCount = readRetryCount(originalProps);
        if (retryCount >= MAX_RETRY_COUNT) {
            log.error("提交消息重试超限（{} 次），转入死信队列", retryCount);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        long delayMillis = BASE_DELAY_MS * (1L << retryCount);
        Message retryMessage = buildRetryMessage(body, originalProps, retryCount + 1, delayMillis);
        // 等待队列无消费者，TTL 到期后经 DLX 弹回 judge.exchange/judge.routing（即本队列）
        rabbitTemplate.send("", MqContexts.JUDGE_WAIT_QUEUE, retryMessage);
        log.info("提交消息已转入等待队列延迟重试: retryCount={}, delayMs={}", retryCount + 1, delayMillis);
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 重建延迟重试消息：消息体字节原样保留，retryCount 头 +1，
     * per-message expiration 控制等待队列的 TTL。
     */
    private Message buildRetryMessage(
            String body,
            MessageProperties originalProps,
            int nextRetryCount,
            long delayMillis
    ) {
        MessageProperties props = new MessageProperties();
        if (originalProps.getContentType() != null) {
            props.setContentType(originalProps.getContentType());
        }
        if (originalProps.getHeaders() != null) {
            props.getHeaders().putAll(originalProps.getHeaders());
        }
        props.setHeader(EventPublisher.HEADER_RETRY_COUNT, nextRetryCount);
        props.setExpiration(String.valueOf(delayMillis));
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    /** 读取 {@code x-codewise-retry-count} 头，缺省或类型异常按 0 处理。 */
    private int readRetryCount(MessageProperties props) {
        Object value = props.getHeader(EventPublisher.HEADER_RETRY_COUNT);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /** 信封格式消息记录 eventId/eventType，便于控制台与日志检索。 */
    private void logEnvelopeIfPresent(String body) {
        if (!EnvelopeCodec.isEnvelope(body)) {
            return;
        }
        try {
            EventEnvelope envelope = EnvelopeCodec.readEnvelope(body);
            log.info("收到判题提交事件: eventId={}, eventType={}, traceId={}",
                    envelope.getEventId(), envelope.getEventType(), envelope.getTraceId());
        } catch (IllegalArgumentException exception) {
            log.warn("信封解析失败，按裸格式继续处理", exception);
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

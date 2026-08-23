package org.example.serviceai.MQ;

import com.rabbitmq.client.Channel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.event.EventPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 队列分发器（ai.testcase.queue + ai.advice.queue，手动 ACK）。
 *
 * <p>失败策略：handler 载荷解析/校验失败（毒消息，IllegalArgumentException）
 * 直接 nack 进 ai.dlx；业务异常按 {@code x-codewise-retry-count} 头指数退避
 * （5s/10s/20s）——小于 3 次时把原始消息体（保持信封/裸格式不变）经默认交换机
 * 投入对应等待队列，TTL 到期后弹回主队列；达到 3 次仍失败则 nack 死信，由
 * {@code AiDeadLetterHandler} 留存。</p>
 */
@Slf4j
@Component
public class Mq {

    /** 最大延迟重试次数，达到后进入死信队列（GLM/Qwen 等终态补偿逻辑共用同一上限）。 */
    public static final int MAX_RETRY_COUNT = 3;

    /** 退避基数（毫秒）：第 n 次重试延迟 5000 * 2^n。 */
    private static final long BASE_DELAY_MS = 5_000L;

    private final List<AiMessageHandler> handlers;
    private final RabbitTemplate rabbitTemplate;
    private Map<String, AiMessageHandler> handlerMap = new HashMap<>();

    // Spring 自动注入所有 AiMessageHandler 的实现类
    public Mq(List<AiMessageHandler> handlers, RabbitTemplate rabbitTemplate) {
        this.handlers = handlers;
        this.rabbitTemplate = rabbitTemplate;
    }




    @PostConstruct
    public void init() {
        // 将 List 转换为 Map，方便根据路由键快速查找
        for (AiMessageHandler handler : handlers) {
            handlerMap.put(handler.getRoutingKey(), handler);
        }
    }

    /**
     * 消费一条 AI 队列消息并按路由键分发给对应 handler。
     *
     * @param routingKey 接收路由键，用于定位 handler 与重试等待队列
     * @param channel    当前 channel，用于手动 ACK/NACK
     * @param amqpMessage 原始 AMQP 消息
     * @throws IOException ACK/NACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = {MqContexts.AI_TESTCASE_QUEUE, MqContexts.AI_ADVICE_QUEUE})
    public void mq(
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
            Channel channel,
            Message amqpMessage
    ) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String body = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        logEnvelopeIfPresent(body, routingKey);

        AiMessageHandler handler = handlerMap.get(routingKey);
        if (handler == null) {
            log.error("没有找到处理路由键 [{}] 的处理器，转入死信: eventId={}, body={}",
                    routingKey, readEventIdQuietly(body), abbreviate(body));
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            handler.handle(body, amqpMessage);
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException exception) {
            // 毒消息：载荷非法重试无意义，直接死信
            log.error("消息载荷解析失败，按毒消息直接死信: routingKey={}, eventId={}, body={}",
                    routingKey, readEventIdQuietly(body), abbreviate(body), exception);
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception exception) {
            log.error("消息处理失败，进入延迟重试或死信: routingKey={}, eventId={}, retryCount={}",
                    routingKey, readEventIdQuietly(body),
                    readRetryCount(amqpMessage.getMessageProperties()), exception);
            scheduleRetryOrDeadLetter(body, routingKey, amqpMessage.getMessageProperties(), channel, deliveryTag);
        }
    }

    /**
     * 业务失败后的统一处置：未超限则原样转投路由键对应的等待队列做延迟重试，超限则死信。
     *
     * @param body          原始消息体字符串（保持信封或裸格式不变）
     * @param routingKey    接收路由键，决定重投的等待队列
     * @param originalProps 原消息属性（携带 retryCount 头）
     * @param channel       当前 channel
     * @param deliveryTag   当前消息 delivery tag
     * @throws IOException ACK/NACK 失败
     */
    private void scheduleRetryOrDeadLetter(
            String body,
            String routingKey,
            MessageProperties originalProps,
            Channel channel,
            long deliveryTag
    ) throws IOException {
        int retryCount = readRetryCount(originalProps);
        if (retryCount >= MAX_RETRY_COUNT) {
            log.error("消息重试超限（{} 次），转入死信队列: routingKey={}, eventId={}",
                    retryCount, routingKey, readEventIdQuietly(body));
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        String waitQueue = resolveWaitQueue(routingKey);
        if (waitQueue == null) {
            // 只有两个主路由键会进入本分发器，其他路由键按死信处理避免消息悬挂
            log.error("无法识别路由键 [{}] 对应的等待队列，按死信处理: eventId={}",
                    routingKey, readEventIdQuietly(body));
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        long delayMillis = BASE_DELAY_MS * (1L << retryCount);
        Message retryMessage = buildRetryMessage(body, originalProps, retryCount + 1, delayMillis);
        // 等待队列无消费者，TTL 到期后经 DLX 弹回 ai.exchange 对应路由键（即主队列）
        rabbitTemplate.send("", waitQueue, retryMessage);
        log.info("消息已转入等待队列延迟重试: routingKey={}, eventId={}, retryCount={}, delayMs={}",
                routingKey, readEventIdQuietly(body), retryCount + 1, delayMillis);
        channel.basicAck(deliveryTag, false);
    }

    /** 按接收路由键定位延迟重试等待队列；非本分发器管理的路由键返回 null。 */
    private String resolveWaitQueue(String routingKey) {
        if (MqContexts.Ai_TESTCASE_ROUTING_KEY.equals(routingKey)) {
            return MqContexts.AI_TESTCASE_WAIT_QUEUE;
        }
        if (MqContexts.AI_WA_ADVICE_ROUTING_KEY.equals(routingKey)) {
            return MqContexts.AI_ADVICE_WAIT_QUEUE;
        }
        return null;
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
    private void logEnvelopeIfPresent(String body, String routingKey) {
        if (!EnvelopeCodec.isEnvelope(body)) {
            log.info("收到消息，路由键: {}", routingKey);
            return;
        }
        try {
            EventEnvelope envelope = EnvelopeCodec.readEnvelope(body);
            log.info("收到事件: eventId={}, eventType={}, traceId={}, routingKey={}",
                    envelope.getEventId(), envelope.getEventType(), envelope.getTraceId(), routingKey);
        } catch (IllegalArgumentException exception) {
            log.warn("信封解析失败，按裸格式继续处理", exception);
        }
    }

    /** 尽力提取 eventId 用于日志检索，非信封格式返回 null。 */
    private String readEventIdQuietly(String body) {
        if (!EnvelopeCodec.isEnvelope(body)) {
            return null;
        }
        try {
            return EnvelopeCodec.readEnvelope(body).getEventId();
        } catch (IllegalArgumentException exception) {
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

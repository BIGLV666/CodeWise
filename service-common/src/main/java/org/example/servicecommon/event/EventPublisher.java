package org.example.servicecommon.event;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 统一事件发布器。
 *
 * <p>所有新链路的 MQ 发布统一走本类：构造 {@link EventEnvelope}，
 * AMQP 头部同步携带 eventId/eventType/producer/traceId 便于控制台检索，
 * 消息体为信封 JSON。消费端配合 {@link EnvelopeCodec#unwrap} 双读。</p>
 *
 * <p>注意：需要「数据库写入与消息发送原子一致」的场景（如 Question -> Judge
 * 主链路）必须改用 OutboxPro（{@code OutboxProPublisher#publish}，在业务事务内
 * 追加 {@code outboxpro_outbox}，由 Relay 经 Publisher Confirm 投递），
 * 不得直接调用本类。本类仅用于事务外的尽力而为发送（如 WebSocket 推送）。</p>
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    /** AMQP 头部：事件 ID */
    public static final String HEADER_EVENT_ID = "eventId";
    /** AMQP 头部：事件类型 */
    public static final String HEADER_EVENT_TYPE = "eventType";
    /** AMQP 头部：载荷版本 */
    public static final String HEADER_SCHEMA_VERSION = "schemaVersion";
    /** AMQP 头部：生产者 */
    public static final String HEADER_PRODUCER = "producer";
    /** AMQP 头部：链路追踪 ID */
    public static final String HEADER_TRACE_ID = "traceId";
    /** AMQP 头部：消费重试次数（延迟重试链路递增） */
    public static final String HEADER_RETRY_COUNT = "x-codewise-retry-count";

    private final RabbitTemplate rabbitTemplate;
    private final String producer;

    public EventPublisher(RabbitTemplate rabbitTemplate, String producer) {
        this.rabbitTemplate = rabbitTemplate;
        this.producer = producer;
    }

    /**
     * 以统一信封发布事件（非事务路径专用）。
     *
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param eventType  事件类型，见 {@code EventTypes}
     * @param payload    业务载荷，约定只放 ID 引用
     */
    public void publish(String exchange, String routingKey, String eventType, Object payload) {
        EventEnvelope envelope = EnvelopeCodec.wrap(eventType, producer, MDC.get("traceId"), payload);
        MessagePostProcessor headerDecorator = message -> {
            var properties = message.getMessageProperties();
            properties.setHeader(HEADER_EVENT_ID, envelope.getEventId());
            properties.setHeader(HEADER_EVENT_TYPE, envelope.getEventType());
            properties.setHeader(HEADER_SCHEMA_VERSION, envelope.getSchemaVersion());
            properties.setHeader(HEADER_PRODUCER, envelope.getProducer());
            properties.setHeader(HEADER_TRACE_ID, envelope.getTraceId());
            properties.setHeader(HEADER_RETRY_COUNT, 0);
            return message;
        };
        rabbitTemplate.convertAndSend(exchange, routingKey, envelope, headerDecorator);
        log.info("事件已发布: eventId={}, eventType={}, exchange={}, routingKey={}",
                envelope.getEventId(), eventType, exchange, routingKey);
    }
}

package org.example.servicecommon.outbox;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.outbox.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Outbox 事件写入服务。
 *
 * <p>必须在调用方的业务事务内调用 {@link #append}：事件行与业务写入
 * 同事务提交/回滚，从根上消除「DB 提交但消息未发出」与「消息已发出
 * 但 DB 回滚」的不一致。事务提交后由 {@link OutboxRelay} 异步投递，
 * 整体达到至少一次投递语义，消费端以 eventId/业务 CAS 幂等兜底。</p>
 */
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxMapper outboxMapper;
    private final String producer;

    public OutboxService(OutboxMapper outboxMapper, String producer) {
        this.outboxMapper = outboxMapper;
        this.producer = producer;
    }

    /**
     * 在当前事务内追加一条待投递事件。
     *
     * @param eventType  事件类型，见 {@code EventTypes}
     * @param exchange   目标交换机
     * @param routingKey 目标路由键
     * @param payload    业务载荷（只放 ID 引用等小字段）
     * @return 写入的事件（含生成的 eventId，便于调用方记日志）
     */
    public OutboxEvent append(String eventType, String exchange, String routingKey, Object payload) {
        EventEnvelope envelope = EnvelopeCodec.wrap(eventType, producer, MDC.get("traceId"), payload);
        OutboxEvent event = OutboxEvent.builder()
                .eventId(envelope.getEventId())
                .eventType(eventType)
                .exchangeName(exchange)
                .routingKey(routingKey)
                .payload(EnvelopeCodec.serialize(envelope))
                .producer(producer)
                .status(OutboxEvent.STATUS_PENDING)
                .retryCount(0)
                .build();
        outboxMapper.insert(event);
        log.info("Outbox 事件已登记: eventId={}, eventType={}, routingKey={}",
                event.getEventId(), eventType, routingKey);
        return event;
    }
}

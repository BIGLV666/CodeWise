package org.example.servicecommon.outbox;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicecommon.outbox.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 中转投递器。
 *
 * <p>每秒批量认领到期 PENDING 事件（FOR UPDATE SKIP LOCKED，多实例安全），
 * 按原始信封 JSON 直发 MQ 并标记 SENT；投递失败按指数退避（10s * 2^n）
 * 重试，超过 {@link #MAX_RETRY} 次转 DEAD，等待人工重放。</p>
 *
 * <p>可靠性说明：当前以「发送不抛异常」视为投递成功（至少一次），
 * broker 侧重复由消费端幂等吸收；后续在 Nacos 开启 publisher confirm 后，
 * 可在本类升级为 confirm 确认后再标记 SENT，无需改动调用方。</p>
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 单批认领上限 */
    private static final int BATCH_SIZE = 100;
    /** 最大投递重试次数，超过后转 DEAD */
    private static final int MAX_RETRY = 8;
    /** 退避基数（秒）：第 n 次失败后延迟 10 * 2^n 秒 */
    private static final long BASE_BACKOFF_SECONDS = 10;
    /** last_error 字段截断长度 */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxMapper outboxMapper;
    private final EventPublisher eventPublisher;

    public OutboxRelay(OutboxMapper outboxMapper, EventPublisher eventPublisher) {
        this.outboxMapper = outboxMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 定时投递入口。事务直接标注在调度方法上（调度器经由代理调用），
     * 批内单条失败只影响该条的状态推进，不回滚其他已成功条目。
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxMapper.claimPendingBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox 本批认领 {} 条待投递事件", batch.size());
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    /**
     * 投递单条事件：成功标记 SENT；失败进入退避或转 DEAD。
     * 任何异常都在方法内消化，避免单条故障阻断整批。
     */
    private void publishOne(OutboxEvent event) {
        try {
            EventEnvelope envelope = EnvelopeCodec.readEnvelope(event.getPayload());
            eventPublisher.publishRawEnvelope(
                    event.getExchangeName(),
                    event.getRoutingKey(),
                    envelope,
                    event.getPayload(),
                    0);
            event.setStatus(OutboxEvent.STATUS_SENT);
            event.setSentTime(LocalDateTime.now());
            outboxMapper.updateById(event);
        } catch (Exception exception) {
            markFailure(event, exception);
        }
    }

    /**
     * 失败处理：未超限则指数退避后重试，超限转 DEAD。
     */
    private void markFailure(OutboxEvent event, Exception exception) {
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        retryCount = retryCount + 1;
        event.setRetryCount(retryCount);
        event.setLastError(truncateError(exception));

        if (retryCount >= MAX_RETRY) {
            event.setStatus(OutboxEvent.STATUS_DEAD);
            event.setNextRetryTime(null);
            log.error("Outbox 事件投递超限转 DEAD: outboxId={}, eventId={}, retryCount={}",
                    event.getOutboxId(), event.getEventId(), retryCount);
        } else {
            long delaySeconds = BASE_BACKOFF_SECONDS * (1L << (retryCount - 1));
            event.setNextRetryTime(LocalDateTime.now().plusSeconds(delaySeconds));
            log.warn("Outbox 事件投递失败，将于 {}s 后重试: outboxId={}, eventId={}, 原因={}",
                    delaySeconds, event.getOutboxId(), event.getEventId(), exception.getMessage());
        }
        outboxMapper.updateById(event);
    }

    private String truncateError(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}

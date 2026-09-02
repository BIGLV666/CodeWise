package org.example.servicecommon.outboxpro;

import org.outboxpro.spi.deadletter.DeadLetterAlertNotifier;
import org.outboxpro.spi.deadletter.DeadLetterContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.atomic.AtomicLong;

/**
 * CodeWise 死信告警通知器：把 OutboxPro 的死信信号落到 ERROR/WARN 日志与 Micrometer 指标。
 *
 * <p>通知器是旁路能力：所有方法都不抛异常、不做 IO 阻塞操作，绝不影响
 * 消息消费、死信落库或重放的主流程。</p>
 *
 * <ul>
 *   <li>{@link #notify}：每条死信落 DLQ 时输出 WARN（含事件/消费者/原因/尝试次数）；</li>
 *   <li>{@link #onHighWatermark}：待重放死信积压达到阈值（默认 100）时输出 ERROR，
 *       这是「坏了有人第一个知道」的最小闭环；</li>
 *   <li>{@link #onRecovered}：积压回落时输出 INFO。</li>
 * </ul>
 *
 * <p>指标：{@code codewise.outboxpro.deadletter}（tag: reason）计数器 + 高水位
 * active 标记（gauge 语义经 counter 0/1 切换表达），供 Prometheus 采集。</p>
 */
public class CodewiseOutboxAlertNotifier implements DeadLetterAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger("OUTBOXPRO_ALERT");
    private static final String METRIC_DEADLETTER = "codewise.outboxpro.deadletter";
    private static final String METRIC_BACKLOG = "codewise.outboxpro.deadletter.backlog";

    private final AtomicLong backlogState = new AtomicLong();

    @Lazy
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

    @Override
    public void notify(DeadLetterContext context) {
        try {
            log.warn("OutboxPro 死信: eventId={}, eventType={}, consumer={}, queue={}, attempt={}, reason={}",
                    context.eventId(), context.eventType(), context.consumerName(), context.queue(),
                    context.attempt(), context.reason());
            var registry = meterRegistry != null ? meterRegistry.getIfAvailable() : null;
            if (registry != null) {
                registry.counter(METRIC_DEADLETTER,
                        "reason", String.valueOf(context.reason())).increment();
            }
        } catch (RuntimeException ignored) {
            // 旁路能力，绝不影响主流程
        }
    }

    @Override
    public void onHighWatermark(long pendingCount, long threshold) {
        try {
            backlogState.set(1);
            log.error("OutboxPro 死信积压达到高水位: pendingCount={}, threshold={}, 需人工介入"
                            + "（查询 /actuator/outboxpro-ops/dlq 或 RabbitMQ 控制台 *.dlq 队列）",
                    pendingCount, threshold);
            var registry = meterRegistry != null ? meterRegistry.getIfAvailable() : null;
            if (registry != null) {
                registry.gauge(METRIC_BACKLOG, backlogState, AtomicLong::get);
            }
        } catch (RuntimeException ignored) {
            // 旁路能力
        }
    }

    @Override
    public void onRecovered(long pendingCount, long recoveryThreshold) {
        try {
            backlogState.set(0);
            log.info("OutboxPro 死信积压恢复: pendingCount={}, recoveryThreshold={}", pendingCount, recoveryThreshold);
        } catch (RuntimeException ignored) {
            // 旁路能力
        }
    }
}

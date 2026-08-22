package org.example.serviceapi.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一 MQ 消息信封。
 *
 * <p>所有新链路的 MQ 消息均以该信封作为消息体，AMQP 头部同时携带
 * eventId/eventType 等关键字段以便运维检索。消费端通过
 * {@code service-common} 的 {@code EnvelopeCodec#unwrap} 做信封/裸格式双读，
 * 保证灰度期间旧格式消息仍可消费。</p>
 *
 * <p>约定：payload 只放 ID 引用等小字段，代码、日志、程序输出等大字段
 * 一律落库后按需拉取，避免 MQ 大消息。</p>
 *
 * @param eventId       事件唯一 ID（UUID），消费端幂等键与审计键
 * @param eventType     事件类型，见 {@link EventTypes}
 * @param schemaVersion 载荷结构版本，首版为 1
 * @param occurredAt    事件产生时间（ISO-8601 字符串，避免消费端缺少 JavaTimeModule）
 * @param producer      生产者服务名，如 service-question
 * @param traceId       链路追踪 ID，优先取 MDC 中的 traceId，无则生成短 ID
 * @param payload       业务载荷（主链路只放 ID 引用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前信封结构版本 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private String eventId;
    private String eventType;
    private Integer schemaVersion;
    private String occurredAt;
    private String producer;
    private String traceId;
    private Object payload;
}

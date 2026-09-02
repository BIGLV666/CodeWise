package org.example.servicecommon.outboxpro;

import org.example.servicecommon.event.EnvelopeCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费端 wire 兼容契约测试：CodeWise 全部消费者经 {@link EnvelopeCodec#unwrap}
 * 解析消息体，本测试锁定「OutboxPro 1.1.0 默认序列化形状」可被无损解包。
 *
 * <p>OutboxPro 的信封与 CodeWise 的 EventEnvelope 差异点（均为兼容项）：
 * schemaVersion 是 "v1" 字符串（CodeWise 为整数，但 unwrap 不读该字段）、
 * 多出 correlationId/causationId/extensions 字段（unwrap 只取 root.payload）、
 * occurredAt 是 Instant 格式（unwrap 不读该字段）。</p>
 *
 * <p>若未来 OutboxPro 升级改变了消息体顶层结构（去掉 eventId/eventType 或
 * 改名 payload），本测试应第一时间失败。</p>
 */
class OutboxWireCompatTest {

    /** OutboxPro JacksonEventSerializer 输出的完整信封形状（字段名逐字对齐 1.1.0 实现） */
    private static final String OUTBOXPRO_BODY = "{"
            + "\"eventId\":\"3f6d2c9e-8a1b-4c3d-9e2f-5a6b7c8d9e0f\","
            + "\"eventType\":\"judge.submit.request\","
            + "\"schemaVersion\":\"v1\","
            + "\"producer\":\"service-question\","
            + "\"occurredAt\":\"2026-09-02T08:30:00Z\","
            + "\"traceId\":\"tr-abc123\","
            + "\"correlationId\":\"cor-abc123\","
            + "\"causationId\":null,"
            + "\"payload\":{\"submitRecordId\":1024},"
            + "\"extensions\":{}"
            + "}";

    /** CodeWise 业务载荷 DTO 形状 */
    record SubmitPayload(Long submitRecordId) { }

    @Test
    void unwrapExtractsPayloadFromOutboxProEnvelope() {
        assertThat(EnvelopeCodec.isEnvelope(OUTBOXPRO_BODY)).isTrue();
        SubmitPayload payload = EnvelopeCodec.unwrap(OUTBOXPRO_BODY, SubmitPayload.class);
        assertThat(payload.submitRecordId()).isEqualTo(1024L);
    }

    @Test
    void readEnvelopeToleratesOutboxProEnvelopeShape() {
        // 消费分发路径（如 service-ai Mq）经 readEnvelope 取 eventType/eventId：
        // OutboxPro 的 schemaVersion 是 "v1" 字符串、多出 correlationId 等字段，必须可解析
        var envelope = EnvelopeCodec.readEnvelope(OUTBOXPRO_BODY);
        assertThat(envelope.getEventId()).isEqualTo("3f6d2c9e-8a1b-4c3d-9e2f-5a6b7c8d9e0f");
        assertThat(envelope.getEventType()).isEqualTo("judge.submit.request");
        assertThat(envelope.getProducer()).isEqualTo("service-question");
        assertThat(envelope.getPayload()).isNotNull();
    }

    @Test
    void unwrapStillReadsBareLegacyBody() {
        // 灰度双读语义不被破坏：裸 JSON（无信封）按目标类型直接反序列化
        String bare = "{\"submitRecordId\":2048}";
        SubmitPayload payload = EnvelopeCodec.unwrap(bare, SubmitPayload.class);
        assertThat(payload.submitRecordId()).isEqualTo(2048L);
    }
}

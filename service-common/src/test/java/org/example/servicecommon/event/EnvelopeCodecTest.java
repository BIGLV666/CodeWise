package org.example.servicecommon.event;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 信封编解码器单元测试：覆盖信封格式与灰度期旧裸格式的双读兼容。
 */
class EnvelopeCodecTest {

    @Test
    void wrap应生成完整信封字段() {
        EventEnvelope envelope = EnvelopeCodec.wrap("JUDGE_SUBMIT_REQUEST", "service-question", null, 123L);

        assertNotNull(envelope.getEventId());
        assertEquals("JUDGE_SUBMIT_REQUEST", envelope.getEventType());
        assertEquals(EventEnvelope.CURRENT_SCHEMA_VERSION, envelope.getSchemaVersion());
        assertNotNull(envelope.getOccurredAt());
        assertEquals("service-question", envelope.getProducer());
        assertNotNull(envelope.getTraceId());
        assertEquals(123L, envelope.getPayload());
    }

    @Test
    void unwrap应解包信封载荷为Long() {
        EventEnvelope envelope = EnvelopeCodec.wrap("JUDGE_SUBMIT_REQUEST", "service-question", "t-1", 42L);
        String body = EnvelopeCodec.serialize(envelope);

        assertTrue(EnvelopeCodec.isEnvelope(body));
        Long payload = EnvelopeCodec.unwrap(body, Long.class);
        assertEquals(42L, payload);
    }

    @Test
    void unwrap应兼容旧裸Long格式() {
        String legacyBody = "123";

        assertFalse(EnvelopeCodec.isEnvelope(legacyBody));
        Long payload = EnvelopeCodec.unwrap(legacyBody, Long.class);
        assertEquals(123L, payload);
    }

    @Test
    void unwrap应兼容旧裸字符串格式() {
        String legacyBody = "\"debug-uuid-1\"";

        String payload = EnvelopeCodec.unwrap(legacyBody, String.class);
        assertEquals("debug-uuid-1", payload);
    }

    @Test
    void unwrap应兼容旧裸DTO格式() {
        String legacyBody = "{\"userId\":1,\"submitId\":2,\"questionId\":3,"
                + "\"messageId\":\"m-1\",\"language\":\"java\",\"judgeStatus\":\"WA\",\"judgeRecordId\":9}";

        org.example.serviceapi.dto.ai.AiAdviceWADto payload =
                EnvelopeCodec.unwrap(legacyBody, org.example.serviceapi.dto.ai.AiAdviceWADto.class);

        assertEquals(1L, payload.getUserId());
        assertEquals(9L, payload.getJudgeRecordId());
        assertEquals("WA", payload.getJudgeStatus());
    }

    @Test
    void unwrap信封内DTO载荷() {
        org.example.serviceapi.dto.ai.AiAdviceWADto slim = org.example.serviceapi.dto.ai.AiAdviceWADto.builder()
                .userId(7L).submitId(8L).questionId(9L).judgeRecordId(10L)
                .messageId("m-2").language("java").judgeStatus("TLE").build();
        EventEnvelope envelope = EnvelopeCodec.wrap("AI_ADVICE_REQUEST", "service-judge", null, slim);

        org.example.serviceapi.dto.ai.AiAdviceWADto payload =
                EnvelopeCodec.unwrap(EnvelopeCodec.serialize(envelope), org.example.serviceapi.dto.ai.AiAdviceWADto.class);

        assertEquals(7L, payload.getUserId());
        assertEquals(10L, payload.getJudgeRecordId());
        assertEquals("TLE", payload.getJudgeStatus());
    }

    @Test
    void readEnvelope应还原信封并拒绝裸格式() {
        EventEnvelope envelope = EnvelopeCodec.wrap("JUDGE_RESULT_CALLBACK", "service-judge", "t-2", 99L);
        EventEnvelope parsed = EnvelopeCodec.readEnvelope(EnvelopeCodec.serialize(envelope));

        assertEquals(envelope.getEventId(), parsed.getEventId());
        assertEquals(99L, ((Number) parsed.getPayload()).longValue());

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> EnvelopeCodec.readEnvelope("123"));
        assertTrue(exception.getMessage().contains("信封"));
    }
}

package org.example.servicecommon.outboxpro;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.event.EnvelopeCodec;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relay 发布模板的编码契约测试。
 *
 * <p>回归护栏：{@code RabbitMessagePublisher} 交给 {@code convertAndSend} 的是**已序列化的信封
 * 字符串**，必须原样出网。曾经因为 Relay 复用应用侧带 Jackson 转换器的模板，信封被二次
 * JSON 编码（JSON 引号套 JSON），消费端 {@code EnvelopeCodec#unwrap} 解析失败、
 * JUDGE_SUBMIT_REQUEST 全部被判毒消息丢弃，提交永久 PENDING（2026-10-13 线上实测）。</p>
 */
class OutboxProRelaySupportTest {

    /** 生产者真实产出的信封体：与 JudgeService 发布 JUDGE_SUBMIT_REQUEST 的形状一致。 */
    private static String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap("JUDGE_SUBMIT_REQUEST", "service-question", "t-1", 42L);
        return EnvelopeCodec.serialize(envelope);
    }

    private static String bodyOf(RabbitTemplate template, String payload) {
        Message message = template.getMessageConverter().toMessage(payload, new MessageProperties());
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    /** Relay 专用模板：字符串原样字节出网。 */
    private static RabbitTemplate relayTemplate() {
        return OutboxProRelaySupport.relayRabbitTemplate(new CachingConnectionFactory("localhost"));
    }

    /** 应用侧模板：带 Jackson 转换器（直发 EventEnvelope 等对象需要）。 */
    private static RabbitTemplate jacksonTemplate() {
        RabbitTemplate template = new RabbitTemplate(new CachingConnectionFactory("localhost"));
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }

    @Test
    void relay模板应让信封字符串原样出网() {
        String envelope = envelopeBody();

        assertEquals(envelope, bodyOf(relayTemplate(), envelope));
    }

    @Test
    void 原样出网后消费端应能解出业务载荷() {
        String body = bodyOf(relayTemplate(), envelopeBody());

        assertTrue(EnvelopeCodec.isEnvelope(body));
        assertEquals(42L, EnvelopeCodec.unwrap(body, Long.class));
    }

    @Test
    void Jackson转换器会二次编码并使消费端解析失败() {
        // 说明为什么 Relay 不能复用应用侧模板：多包一层引号后，unwrap 把整串信封当裸载荷。
        final String envelope = envelopeBody();
        final String doubleEncoded = bodyOf(jacksonTemplate(), envelope);

        assertNotEquals(envelope, doubleEncoded);
        assertTrue(doubleEncoded.startsWith("\"{"), "二次编码后应以 JSON 引号开头: " + doubleEncoded);
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCodec.unwrap(doubleEncoded, Long.class));
    }
}

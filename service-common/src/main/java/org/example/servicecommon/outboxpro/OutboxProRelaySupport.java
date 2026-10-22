package org.example.servicecommon.outboxpro;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

/**
 * OutboxPro Relay 发布侧的模板装配。
 *
 * <p>{@code RabbitMessagePublisher} 用 {@code convertAndSend(exchange, routingKey, payloadJson)}
 * 发送**已序列化好的信封 JSON 字符串**（框架随后自行设置 {@code contentType=application/json}），
 * 要求它以原样字节出网。因此承载 Relay 的模板必须用「原样字节」转换器：若沿用应用侧那台带
 * {@code Jackson2JsonMessageConverter} 的模板，这个字符串会被再序列化一层，消息体变成
 * {@code "{\"eventId\":...}"}（JSON 引号套 JSON）。消费端 {@code EnvelopeCodec#unwrap} 只解一层，
 * 会把整串信封当成裸载荷去反序列化而失败，消息被判为毒消息丢弃——提交判题于是永久停在 PENDING。</p>
 *
 * <p>反向也不行：应用侧模板不能改成原样转换器。{@code EventPublisher} 直接发送
 * {@code EventEnvelope} 对象，另有若干裸标量（调试任务 UUID、submitRecordId）依赖 JSON 转换器
 * 才成为合法 JSON。两类载荷的编码要求相反，所以 Relay 用独立模板，应用侧模板保持不变。</p>
 */
public final class OutboxProRelaySupport {

    private OutboxProRelaySupport() {
    }

    /**
     * 构建 Relay 专用模板：字符串按 UTF-8 原样出网，不再额外包一层 JSON 引号。
     *
     * @param connectionFactory 连接工厂（Spring Boot 默认的 CachingConnectionFactory，
     *                          Publisher Confirm 语义由 {@code RabbitMessagePublisher} 在其上开启）
     * @return 原样字节语义的 RabbitTemplate
     */
    public static RabbitTemplate relayRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new SimpleMessageConverter());
        return template;
    }
}

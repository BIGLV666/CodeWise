package org.example.serviceai.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务 RabbitTemplate 定制。
 *
 * <p>service-ai 有多处经 RabbitTemplate 直发 POJO 的场景（AI 建议通知 NotificationDto、
 * 用例生成结果 List&lt;TestMessage&gt;、WebSocket 推送 WebSocketSendDto）。这些 POJO 不可
 * Serializable，RabbitTemplate 若停留在默认的 SimpleMessageConverter 会在发送时抛
 * 「SimpleMessageConverter only supports String, byte[] and Serializable payloads」，
 * 导致业务处理到通知一步失败、整条事件被当毒消息死信——2026-09-12 压测实测复现
 * （真实模型生成成功、通知丢失）。</p>
 *
 * <p>显式声明带 Jackson2JsonMessageConverter 的 RabbitTemplate（与 service-common MqConfig
 * 的 converter 保持同一 JSON 约定）：Bean 存在后 Boot 自动装配回退，消费端
 * （message/question 侧 readValue 裸 JSON）与既有信封格式均兼容。</p>
 */
@Configuration
public class AiRabbitTemplateConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}

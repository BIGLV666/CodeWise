package org.example.serviceai.MQ;

import org.springframework.amqp.core.Message;

public interface AiMessageHandler {
    /**
     * 匹配的路由键
     */
    String getRoutingKey();

    /**
     * 处理消息。
     *
     * <p>handler 不接触 Channel、不自行 ACK/NACK：正常返回由
     * {@code Mq#mq} 统一 basicAck；载荷解析/校验失败（毒消息）抛
     * {@link IllegalArgumentException} 由分发器直接死信；业务失败抛
     * 其他 RuntimeException 由分发器延迟重试或死信。</p>
     *
     * @param message     UTF-8 消息体字符串（信封或裸格式）
     * @param amqpMessage 原始 AMQP 消息
     * @throws Exception 载荷非法（IllegalArgumentException）或业务处理失败
     */
    void handle(String message, Message amqpMessage) throws Exception;
}

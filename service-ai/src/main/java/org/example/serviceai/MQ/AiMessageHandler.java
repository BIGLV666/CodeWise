package org.example.serviceai.MQ;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public interface AiMessageHandler {
    /**
     * 匹配的路由键
     */
    String getRoutingKey();

    /**
     * 处理消息
     */
    void handle(String message, Channel channel, Message amqpMessage)throws IOException;
}

package org.example.servicemessage.mq;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public interface MessageHandler {
    /**
     * 匹配的路由键
     */
    String getRoutingKey();

    /**
     * 处理消息
     */
    void handle(String message, Channel channel, Message amqpMessage)throws IOException;
}

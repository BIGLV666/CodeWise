package org.example.serviceuser.mq;


import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public interface MessageHandler {
    String getRoutingKey();
    void handle(String messageBody, Channel channel, Message amqmessage) throws IOException;
}

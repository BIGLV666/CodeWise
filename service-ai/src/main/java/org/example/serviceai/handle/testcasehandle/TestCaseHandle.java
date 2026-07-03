package org.example.serviceai.handle.testcasehandle;

import com.rabbitmq.client.Channel;
import org.example.serviceai.MQ.AiMessageHandler;
import org.springframework.amqp.core.Message;

import java.io.IOException;

public interface TestCaseHandle extends AiMessageHandler {
    String getRoutingKey();
    void handle(String message, Channel channel, Message amqpMessage)throws IOException;

}

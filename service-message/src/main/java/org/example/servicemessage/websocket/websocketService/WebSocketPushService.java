package org.example.servicemessage.websocket.websocketService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicemessage.mq.MessageHandler;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class WebSocketPushService implements MessageHandler {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void pushToTopic(String topic, Object payload) {
        messagingTemplate.convertAndSend(topic, payload);
    }

    public void pushToUserQueue(Long userId, String queueName, Object payload) {
        String destination = "/queue/" + queueName + "-" + userId;

        messagingTemplate.convertAndSend(destination, payload);
    }

    @Override
    public String getRoutingKey() {
        return MqContexts.WEBSOCKET_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        WebsocketSendDto websocketSendDto = objectMapper.readValue(message, WebsocketSendDto.class);
        Long tag=amqpMessage.getMessageProperties().getDeliveryTag();
        try{
            if(websocketSendDto.getQueueName().equals(WebsocketContexts.TOPIC)){
                pushToTopic(websocketSendDto.getQueueName(),websocketSendDto.getResult());
                channel.basicAck(tag,false);
                return;
            }
            pushToUserQueue(websocketSendDto.getUserId(), websocketSendDto.getQueueName(), websocketSendDto);
            channel.basicAck(tag,false);
        }catch (Exception e){
            channel.basicNack(tag,false,false);
            throw new IllegalStateException(e.getMessage());
        }
    }
}

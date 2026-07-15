package org.example.servicemessage.mq;

import com.rabbitmq.client.Channel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class Mq {
    private final List<MessageHandler> handlers;
    private final Map<String, MessageHandler> handlerMap = new HashMap<>();

    public Mq(List<MessageHandler> handlers) {
        this.handlers = handlers;
    }

    /** 把不同路由键映射到对应的消息处理器。 */
    @PostConstruct
    public void init() {
        for (MessageHandler handler : handlers) {
            MessageHandler previous = handlerMap.put(handler.getRoutingKey(), handler);
            if (previous != null) {
                throw new IllegalStateException("存在重复的消息路由处理器：" + handler.getRoutingKey());
            }
        }
    }

    /** 同时消费邮件/WebSocket 队列和通知中心队列。ACK/NACK 由具体处理器负责。 */
    @RabbitListener(queues = {MqContexts.MESSAGE_QUEUE_NAME, MqContexts.NOTIFICATION_QUEUE_NAME})
    public void consume(Message message,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
                        Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        MessageHandler handler = handlerMap.get(routingKey);
        if (handler == null) {
            log.error("没有找到消息处理器，routingKey={}，deliveryTag={}", routingKey, deliveryTag);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("收到消息，routingKey={}，deliveryTag={}", routingKey, deliveryTag);
        handler.handle(messageBody, channel, message);
    }
}

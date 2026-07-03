package org.example.serviceai.MQ;

import com.rabbitmq.client.Channel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.serviceQuestion.TestCaseBuilder;
import org.example.servicecommon.config.MqContexts;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class Mq {

    @Autowired
    private TestCaseBuilder testCaseBuilder;

    private final List<AiMessageHandler> handlers;
    private Map<String, AiMessageHandler> handlerMap = new HashMap<>();
    // Spring 自动注入所有 AiMessageHandler 的实现类
    public Mq(List<AiMessageHandler> handlers) {
        this.handlers = handlers;
    }




    @PostConstruct
    public void init() {
        // 将 List 转换为 Map，方便根据路由键快速查找
        for (AiMessageHandler handler : handlers) {
            handlerMap.put(handler.getRoutingKey(), handler);
        }
    }

    @RabbitListener(queues = MqContexts.Ai_QUEUE_NAME)
    public void mq( @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        System.out.println("收到消息，路由键: " + routingKey + "，deliveryTag: " + deliveryTag);
        String messageBody = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        try {
            AiMessageHandler handler = handlerMap.get(routingKey);
            if (handler != null) {
                // 执行业务逻辑
                handler.handle(messageBody, channel, amqpMessage);

                // 注意：ACK操作建议放在具体的Handler中执行，或者在这里统一执行
                // 如果在这里统一执行，Handler就不要执行ACK了
            } else {
                System.err.println("没有找到处理路由键 [" + routingKey + "] 的处理器！");
                // 没有对应的处理器，拒绝消息，不重新入队（false表示不重新入队）
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception e) {
            System.err.println("消息处理异常: " + e);
            try {
                // 发生异常，拒绝消息。第三个参数 false 表示不重新入队（会进入死信队列）
                // 如果设为 true，会不断重试，可能导致死循环
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                log.error("e: ", ex);
            }
        }
    }
}


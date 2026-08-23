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

    /** 基础设施异常（如 DB 抖动导致幂等 claim 失败）时的重投退避，避免无退避热循环；测试可调零。 */
    long infraBackoffMs = 5_000L;

    private final List<MessageHandler> handlers;
    /** 路由键 → 处理器注册表（包可见便于单测注入）。 */
    final Map<String, MessageHandler> handlerMap = new HashMap<>();

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

    /**
     * 同时消费邮件/WebSocket 队列和通知中心队列。业务失败由处理器自行 ACK/NACK；
     * 处理器自身 try/catch 之外的异常（如 DB 抖动导致 claim/recordFailure 失败）在此兜底：
     * 退避后 nack 重投，消息不丢（这两个队列未挂 DLX，nack(requeue=false) 等于丢弃）。
     */
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
        try {
            handler.handle(messageBody, channel, message);
        } catch (Exception exception) {
            log.error("消息处理抛出未捕获异常，退避 {}ms 后重投: routingKey={}, deliveryTag={}",
                    infraBackoffMs, routingKey, deliveryTag, exception);
            sleepQuietly();
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /** 退避等待，被中断时恢复中断标记后继续重投（消息优先不丢）。 */
    private void sleepQuietly() {
        if (infraBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(infraBackoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

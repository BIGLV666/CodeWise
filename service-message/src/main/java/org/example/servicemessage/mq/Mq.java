package org.example.servicemessage.mq;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PostConstruct;
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

@Component
public class Mq {

    @Autowired


    private final List<MessageHandler> handlers;
    private Map<String, MessageHandler> handlerMap = new HashMap<>();
    // Spring 鑷姩娉ㄥ叆鎵€鏈?MessageHandler 鐨勫疄鐜扮被
    public Mq(List<MessageHandler> handlers) {
        this.handlers = handlers;
    }




    @PostConstruct
    public void init() {
        // 灏?List 杞崲涓?Map锛屾柟渚挎牴鎹矾鐢遍敭蹇€熸煡鎵?
        for (MessageHandler handler : handlers) {
            handlerMap.put(handler.getRoutingKey(), handler);
        }
    }

    @RabbitListener(queues = MqContexts.MESSAGE_QUEUE_NAME)
    public void mq( Message message, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        System.out.println("鏀跺埌娑堟伅锛岃矾鐢遍敭: " + routingKey + "锛宒eliveryTag: " + deliveryTag);
        String messageBody = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        try {
            MessageHandler handler = handlerMap.get(routingKey);
            if (handler != null) {
                // 鎵ц涓氬姟閫昏緫
                handler.handle(messageBody, channel, amqpMessage);

                // 娉ㄦ剰锛欰CK鎿嶄綔寤鸿鏀惧湪鍏蜂綋鐨凥andler涓墽琛岋紝鎴栬€呭湪杩欓噷缁熶竴鎵ц
                // 濡傛灉鍦ㄨ繖閲岀粺涓€鎵ц锛孒andler灏变笉瑕佹墽琛孉CK浜?
            } else {
                System.err.println("娌℃湁鎵惧埌澶勭悊璺敱閿?[" + routingKey + "] 鐨勫鐞嗗櫒锛?);
                // 娌℃湁瀵瑰簲鐨勫鐞嗗櫒锛屾嫆缁濇秷鎭紝涓嶉噸鏂板叆闃燂紙false琛ㄧず涓嶉噸鏂板叆闃燂級
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception e) {
            System.err.println("娑堟伅澶勭悊寮傚父: " + e.getMessage());
            try {
                // 鍙戠敓寮傚父锛屾嫆缁濇秷鎭€傜涓変釜鍙傛暟 false 琛ㄧず涓嶉噸鏂板叆闃燂紙浼氳繘鍏ユ淇￠槦鍒楋級
                // 濡傛灉璁句负 true锛屼細涓嶆柇閲嶈瘯锛屽彲鑳藉鑷存寰幆
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}

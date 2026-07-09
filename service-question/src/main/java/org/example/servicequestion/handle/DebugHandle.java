package org.example.servicequestion.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.service.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DebugHandle implements MessageHandler {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private WebSocketPushService webSocketPushService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_DEBUG_ROUTING_KEY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        Long taskId = amqpMessage.getMessageProperties().getDeliveryTag();
        String uuid=objectMapper.readValue(amqpMessage.getBody(), String.class);
        try{
            if(uuid==null||uuid.equals("")){
                log.info("未找到该记录{}",uuid);
                throw new RuntimeException("未找到该记录");
            }
            if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.QUESTION_SUCCESS_KEY + uuid, "pending", 5, TimeUnit.MINUTES)))
            {
                log.info("重复消费");
                return;
            }
            List<JudgeReturnRecordDto> ress= (List<JudgeReturnRecordDto>) redisTemplate.opsForHash().get(RedisContext.JUDGE_RESULT_KEY,uuid);
            if(ress==null||ress.isEmpty()){
                throw new RuntimeException("判题结果为空");
            }
            WebsocketSendDto websocketSendDto = new WebsocketSendDto();
            websocketSendDto.setQueueName(WebsocketContexts.DEBUG_JUDGE_RESULT);
            websocketSendDto.setUserId(ress.getFirst().getUserId());
            websocketSendDto.setResult(ress);
            rabbitTemplate.convertAndSend(
                    MqContexts.MESSAGE_EXCHANGE,
                    MqContexts.WEBSOCKET_ROUTING_KEY,
                    websocketSendDto
            );
            redisTemplate.opsForValue().set(RedisContext.QUESTION_SUCCESS_KEY + uuid, "success", 5, TimeUnit.MINUTES);
            channel.basicAck(taskId,false);
            redisTemplate.opsForHash().delete(RedisContext.JUDGE_RESULT_KEY,uuid);

        }catch (Exception e){
            channel.basicNack(taskId,false,false);
            throw new RuntimeException(e);
        }
    }
}

package org.example.serviceuser.MqHandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.mq.MessageHandler;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
@Slf4j
@Component
public class JudgeResHandle implements MessageHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private UserMapper userMapper;

    @Override
    public String getRoutingKey() {
        return MqContexts.USER_JUDGE_ROUTING_KEY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(String messageBody, Channel channel, Message amqmessage) throws IOException {
        Long tag=amqmessage.getMessageProperties().getDeliveryTag();
        try{
            Map<String,Object> map =(Map<String, Object>) objectMapper.readValue(messageBody,Map.class);
            User user=userMapper.selectById((Long)map.get("userId"));
            if(user==null){
                log.info("用户不存在跳过id{}",map.get("userId"));
                channel.basicAck(tag,false);
            }

            user.setTotalSubmit(user.getTotalSubmit()+Long.parseLong((map.get("totalSubmit").toString())));
            user.setTotalAc(user.getTotalAc()+Long.parseLong( map.get("totalAc").toString()));
            int r= userMapper.updateById(user);
            if(r==0){
                log.info("更新失败");
                channel.basicNack(tag,false,true);
            }
        }catch (Exception e){
        log.error("处理判题结果失败", e);
        try {
            // 拒绝并重新入队
            channel.basicNack(tag, false, true);
        } catch (IOException ex) {
            log.error("NACK失败", ex);
        }
        // 抛出异常让事务回滚
        throw new RuntimeException("处理判题结果失败", e);
    }
    }
}

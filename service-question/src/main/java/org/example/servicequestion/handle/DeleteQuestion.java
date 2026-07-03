package org.example.servicequestion.handle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.dto.TestMessage;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.mapper.QuestionMapper;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class DeleteQuestion implements MessageHandler {
    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_DELETE_QUESTION_ROUTING_KEY;
    }
    private ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private QuestionMapper  questionMapper;

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) throws IOException {
        try{
            QuestionMessage messages = objectMapper.readValue(messageBody, new TypeReference<QuestionMessage>() {});
            questionMapper.updateAiStatusToFailure(messages.getQuestionId());
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), true);
        }catch (Exception e){
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(),false,false);
            log.error(e.getMessage(),e);
        }
    }
}

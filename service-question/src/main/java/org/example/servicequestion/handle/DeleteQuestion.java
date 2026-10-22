package org.example.servicequestion.handle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicequestion.MQ.MessageAcknowledgements;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.mapper.QuestionMapper;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Marks AI processing as failed after a question deletion event. */
@Slf4j
@Component
public class DeleteQuestion implements MessageHandler {
    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_DELETE_QUESTION_ROUTING_KEY;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            QuestionMessage message = objectMapper.readValue(messageBody, new TypeReference<QuestionMessage>() {
            });
            questionMapper.updateAiStatusToFailure(message.getQuestionId());
            MessageAcknowledgements.ack(channel, deliveryTag);
        } catch (Exception exception) {
            log.error("删除题目消息处理失败，deliveryTag={}", deliveryTag, exception);
            // This handler owns settlement. Do not rethrow and let Mq NACK the same tag again.
            MessageAcknowledgements.nack(channel, deliveryTag, false);
        }
    }
}

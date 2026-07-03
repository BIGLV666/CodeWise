package org.example.serviceai.handle.testcasehandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.serviceQuestion.TestCaseBuilder;
import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.dto.TestMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class GLMTestCaseHandle implements TestCaseHandle {
    @Autowired
    private TestCaseBuilder testCaseBuilder;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private QuestionFeignClient questionFeignClient;
    @Override
    public String getRoutingKey() {
        return MqContexts.Ai_TESTCASE_ROUTING_KEY;
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) throws IOException {
        QuestionMessage message=null;
        try{
             message = objectMapper.readValue(messageBody, QuestionMessage.class);
            Result<QuestionDto> questionDto=questionFeignClient.getQuestionInfo(message.getQuestionId());
            if(questionDto.getCode()!=200){
                throw new RuntimeException(questionDto.getCode()+":"+questionDto.getMessage());
            }
            if(questionDto.getData()==null){
                throw new RuntimeException(questionDto.getCode()+":"+questionDto.getMessage());
            }
            if(questionDto.getData().getAiStatue().equals("success")){
                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            }
            else{
        List<TestMessage>messages=testCaseBuilder.buildTestCases(message);
        for( TestMessage testMessage:messages){
            testMessage.setCreateUserId(message.getCreateUserId());
        }
        System.out.println("messages:"+messages);
        rabbitTemplate.convertAndSend(
                MqContexts.Question_EXCHANGE,
                MqContexts.Question_TESTCASE_ROUTING_KEY,
                messages
        );
        log.info("用例：{}",message.toString());
        channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);}
        }catch(Exception e){
            log.info("题目:{}",messageBody);


            log.error(e.getMessage(),e);
            if (message != null) {
                rabbitTemplate.convertAndSend(
                        MqContexts.Question_EXCHANGE,
                        MqContexts.QUESTION_DELETE_QUESTION_ROUTING_KEY,
                        message
                        );
            }
            channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(),false,false);
        }

    }
}

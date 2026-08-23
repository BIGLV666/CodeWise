package org.example.serviceai.handle.testcasehandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.MQ.Mq;
import org.example.serviceai.serviceQuestion.TestCaseBuilder;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.dto.TestMessage;
import org.example.servicecommon.event.EventPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Qwen 测试用例生成处理器（路由键 ai.testcase.routing）。
 *
 * <p>ACK/重试/死信纪律统一由 {@code Mq#mq} 分发器负责：载荷解析失败抛
 * {@link IllegalArgumentException}（毒消息死信）；业务失败原样上抛由分发器
 * 延迟重试或死信。「删除题目」补偿仅在终态失败（重试次数已达
 * {@link Mq#MAX_RETRY_COUNT}，本次失败后即死信）时发送，避免重试期间
 * 重复发删除消息、或题目已被删除导致后续重试注定失败。</p>
 */
@Component
@Slf4j
public class QwenTestCaseHandle implements TestCaseHandle {
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
    public void handle(String messageBody, Message amqpMessage) throws Exception {
        QuestionMessage message;
        try{
        message = objectMapper.readValue(messageBody, QuestionMessage.class);
        }catch (JsonProcessingException exception){
            // 毒消息：载荷非法重试无意义，交分发器直接死信
            log.error("用例消息载荷解析失败，按毒消息死信: body={}", messageBody, exception);
            throw new IllegalArgumentException("消息载荷解析失败: " + exception.getMessage(), exception);
        }
        int retryCount = readRetryCount(amqpMessage.getMessageProperties());
        try{
        Result<QuestionDto> questionDto=questionFeignClient.getQuestionInfo(message.getQuestionId());
        if(questionDto.getCode()!=200){
            throw new RuntimeException(questionDto.getCode()+":"+questionDto.getMessage());
        }
        if(questionDto.getData()==null){
            throw new RuntimeException(questionDto.getCode()+":"+questionDto.getMessage());
        }
        if(questionDto.getData().getAiStatue().equals("success")){
            return;
        }
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
    }catch(Exception e){
        log.info("题目:{}",messageBody);


        log.error(e.getMessage(),e);
        if (retryCount >= Mq.MAX_RETRY_COUNT && message != null) {
            // 终态失败补偿：本次失败后分发器即死信，通知 question 服务删除题目（既有独立待办，仅终态发送）
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_DELETE_QUESTION_ROUTING_KEY,
                    message
            );
        }
        // 交分发器统一延迟重试或死信，不再自行 nack
        throw e;
    }

    }

    /** 读取 {@code x-codewise-retry-count} 头（与 {@code Mq#mq} 同读法），缺省按 0 处理。 */
    private int readRetryCount(MessageProperties properties) {
        Object value = properties.getHeader(EventPublisher.HEADER_RETRY_COUNT);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}

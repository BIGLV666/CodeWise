package org.example.servicejudge.Mq.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Mq.MessageHandler;
import org.example.servicejudge.Util.CodeBuild;
import org.example.servicejudge.entry.*;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.functionsService.Java;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 正常判题消息处理器，消费初次提交并回调题目服务。
 * AI 建议消息仍由正常判题流程按原业务规则发送；失败重试由 {@link JudgeRetryHandler} 处理。
 */
@Service
@Slf4j
public class JudgeSubmitHandler implements MessageHandler {

    @Autowired
    private JudgeInterface judge;

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private TestCaseMapper  testCaseMapper;
    @Autowired
    private JudgeRecordMapper judgeRecordMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private FunctionConfigMapper functionConfigMapper;
    @Autowired
    private FunctionTestCaseMapper functionTestCaseMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getRoutingKey() {
        return MqContexts.JUDGE_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws JsonProcessingException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        Long submissionId = objectMapper.readValue(message, Long.class);
        try {

            SubmitRecord submitRecord=submitRecordMapper.selectById(submissionId);
            if(submitRecord==null){
                log.info("submitRecord is null");
                channel.basicAck(deliveryTag, false);
                return;
            }
            if(submitRecord.getJudgeStatus().equals("success")){
                log.info("消息已处理Id{}",submissionId);
                channel.basicAck(deliveryTag,false);
                return;
            }
            if(submitRecordMapper.updateRecordToJudge(submissionId) != 1){
                channel.basicAck(deliveryTag,false);
                return;
            }
            JudgeRecord finalResult=null;
            Question question=questionMapper.selectById(submitRecord.getQuestionId());
           if(question.getQuestionType().equals(QuestionType.ACM)){
            finalResult=ACM(submitRecord);
           }
           if(question.getQuestionType().equals(QuestionType.FUNCTION)){
            finalResult=FUNCTION(submitRecord);
           }

            //构建复习队列

            // 发送结果

            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY, finalResult.getJudgeRecordId());

            //RE建议队列构建
            if(finalResult.getSubmitStatus().equals("WA")||finalResult.getSubmitStatus().equals("RE")||finalResult.getSubmitStatus().equals("TLE")){
                String messageId="ai_advice"+submitRecord.getQuestionId()+":"+submitRecord.getUserId()+":"+finalResult.getJudgeRecordId();
                AiAdviceWADto aiAdviceWADto=new AiAdviceWADto();
                aiAdviceWADto.setCode(finalResult.getCode());
                aiAdviceWADto.setUserId(submitRecord.getUserId());
                aiAdviceWADto.setQuestionId(submitRecord.getQuestionId());
                aiAdviceWADto.setInput(finalResult.getInputData());
                aiAdviceWADto.setUserOutput(finalResult.getUserOutput());
                aiAdviceWADto.setOutput(finalResult.getExpectedOutput());
                aiAdviceWADto.setSubmitId(submissionId);
                aiAdviceWADto.setQuestionContent(question.getDescription());
                aiAdviceWADto.setLanguage(submitRecord.getLanguage());
                aiAdviceWADto.setLog(finalResult.getLog());
                aiAdviceWADto.setJudgeStatus(finalResult.getSubmitStatus());
                aiAdviceWADto.setMessageId(messageId);
                rabbitTemplate.convertAndSend(
                        MqContexts.Ai_EXCHANGE,
                        MqContexts.AI_WA_ADVICE_ROUTING_KEY,
                        aiAdviceWADto

                );
            }


            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("判题处理失败", e);
            try {
                channel.basicNack(deliveryTag, false,false);
            } catch (IOException ex) {
                log.error("NACK失败", ex);
            }
        }
    }
    private List<TestDto>ToTest(List<TestCase> testCases){
        List<TestDto> testDtos = new ArrayList<>();
        for(TestCase testCase:testCases){
            TestDto testDto=new TestDto(testCase);
            testDtos.add(testDto);
        }
        return testDtos;
    }
    private List<TestDto>ToTestDToFroFunction(List<FunctionTestCase> testCases){
        List<TestDto> testDtos = new ArrayList<>();
        for(FunctionTestCase testCase:testCases){
            TestDto testDto=new TestDto(testCase);
            testDtos.add(testDto);
        }
        return testDtos;
    }
    private JudgeRecord ACM(SubmitRecord submitRecord ) throws IOException {


        List<TestCase>testMessages=testCaseMapper.selectList(new QueryWrapper<TestCase>().eq("question_id",submitRecord.getQuestionId()));
        int totalCount = testMessages.size();

        JudgeRecord finalResult = judge.batchExecuteCode(submitRecord.getSubmitContent(), submitRecord.getLanguage(), ToTest(testMessages));

        finalResult.setSubmitRecordId(submitRecord.getSubmitRecordId());
        finalResult.setCode(submitRecord.getSubmitContent());
        finalResult.setCreateTime(LocalDateTime.now());
        finalResult.setTestTotal(totalCount);
        judgeRecordMapper.insert(finalResult);
        return  finalResult;
    }


    private JudgeRecord FUNCTION(SubmitRecord submitRecord ) throws IOException {
        List<FunctionTestCase>testCases=functionTestCaseMapper.selectList(new QueryWrapper<FunctionTestCase>().eq("question_id", submitRecord.getQuestionId()));
        int totalCount = testCases.size();

        FunctionConfig functionConfig=functionConfigMapper.selectOne(new QueryWrapper<FunctionConfig>().eq("question_id", submitRecord.getQuestionId()));
        if(functionConfig==null){
            throw new InterruptedIOException("函数模式题目配置不存在");
        }
        if (!"java".equalsIgnoreCase(submitRecord.getLanguage())) {
            throw new InterruptedIOException("函数模式暂时只支持 Java");
        }

        String main = Java.ToMain(
                functionConfig.getParameterConfig(),
                functionConfig.getMethodName(),
                functionConfig.getOutputType()
        );

        String code = CodeBuild.build(
                submitRecord.getSubmitContent(),
                functionConfig.getParameterConfig(),
                functionConfig.getOutputType()
        );
        long startTime = System.currentTimeMillis();
        JudgeRecord  finalResult=judge.batchExecuteCode(code,main,submitRecord.getLanguage(),ToTestDToFroFunction(testCases));
        long endTime = System.currentTimeMillis();
        finalResult.setSubmitRecordId(submitRecord.getSubmitRecordId());
        finalResult.setCode(submitRecord.getSubmitContent());
        finalResult.setCreateTime(LocalDateTime.now());
        finalResult.setTestTotal(totalCount);
        judgeRecordMapper.insert(finalResult);
        return  finalResult;


    }


}

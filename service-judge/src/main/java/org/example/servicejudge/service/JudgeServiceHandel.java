package org.example.servicejudge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Mq.MessageHandler;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.Question;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.entry.TestCase;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.JudgeRecordMapper;
import org.example.servicejudge.mapper.QuestionMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.example.servicejudge.mapper.TestCaseMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class JudgeServiceHandel implements MessageHandler {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getRoutingKey() {
        return MqContexts.JUDGE_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) {
        long start = System.currentTimeMillis();
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        long testSelect = 0;
        long forstart=0;
        long forstartend=0;
        try {
            Long submissionId = objectMapper.readValue(message, Long.class);
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
            List<TestCase>testMessages=testCaseMapper.selectList(new QueryWrapper<TestCase>().eq("question_id",submitRecord.getQuestionId()));
            testSelect=System.currentTimeMillis();
            log.info("收到判题请求, submissionId: {}, 测试用例数: {}", submissionId,
                    testMessages != null ? testMessages.size() : 0);

            if (testMessages == null || testMessages.isEmpty()) {
                log.info("testMessages is empty");
                channel.basicAck(deliveryTag, false);
                return;
            }

            int totalCount = testMessages.size();
            forstart=System.currentTimeMillis();
            JudgeRecord finalResult = judge.batchExecuteCode(submitRecord.getSubmitContent(), submitRecord.getLanguage(), testMessages);
            forstartend=System.currentTimeMillis();
            int passedCount = "AC".equals(finalResult.getSubmitStatus()) ? totalCount : Math.max(finalResult.getFailIndex() - 1, 0);
            finalResult.setSubmitRecordId(submissionId);
            finalResult.setCode(submitRecord.getSubmitContent());
            finalResult.setCreateTime(LocalDateTime.now());
            finalResult.setTestTotal(totalCount);
             judgeRecordMapper.insert(finalResult);


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
                Question question=questionMapper.selectById(submitRecord.getQuestionId());
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


            log.info("判题完成, submissionId: {}, 通过: {}/{}", submissionId, passedCount, totalCount);
            long end = System.currentTimeMillis();
            log.info("查询结束用时{},循环用时{}，总{}", ( testSelect- start),(forstartend-forstart),(end-start));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("判题处理失败", e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ex) {
                log.error("NACK失败", ex);
            }
        }
    }


}

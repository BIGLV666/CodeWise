package org.example.servicejudge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.JudgeMqDto;
import org.example.serviceapi.dto.JudgeResultDto;
import org.example.serviceapi.dto.TestMessage;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Mq.MessageHandler;
import org.example.servicejudge.Util.BuildResult;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.entry.TestCase;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.JudgeRecordMapper;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getRoutingKey() {
        return MqContexts.JUDGE_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            Long submissionId = objectMapper.readValue(message, Long.class);
            SubmitRecord submitRecord=submitRecordMapper.selectById(submissionId);
            if(submitRecord==null){
                log.info("submitRecord is null");
                channel.basicAck(deliveryTag, false);
            }
            if(submitRecord.getJudgeStatus().equals("success")){
                log.info("消息已处理Id{}",submissionId);
                channel.basicAck(deliveryTag,false);
                return;
            }
            List<TestCase>testMessages=testCaseMapper.selectList(new QueryWrapper<TestCase>().eq("question_id",submitRecord.getQuestionId()));
            log.info("收到判题请求, submissionId: {}, 测试用例数: {}", submissionId,
                    testMessages != null ? testMessages.size() : 0);

            if (testMessages == null || testMessages.isEmpty()) {
                log.info("testMessages is empty");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ★ 循环执行所有测试用例
            JudgeRecord finalResult = null;
            int passedCount = 0;
            int totalCount = testMessages.size();

            for (int i = 0; i < testMessages.size(); i++) {
                TestCase testMessage = testMessages.get(i);
                JudgeReturnDto resultDto = judge.executeCode(submitRecord.getSubmitContent(), submitRecord.getLanguage(), testMessage.getInputData());
                JudgeRecord result= BuildResult.buildResult(resultDto,testMessage.getCaseId(),testMessage.getInputData(),testMessage.getExpectedOutput(),i+1,submitRecord.getLanguage());

                log.info("测试用例 {}/{}: {}", i + 1, totalCount, result.getSubmitStatus());


                    // ...

                // 如果某个用例失败，记录失败信息
                if (!"AC".equals(result.getSubmitStatus())) {

                    result.setFailIndex(i + 1);  // 失败索引从1开始
                    finalResult = result;
                    break;
                }

                // 更新最后成功的结果
                finalResult = result;
                passedCount++;
            }

            // 所有用例都通过
            if (passedCount == totalCount && finalResult != null) {
                finalResult.setSubmitRecordId(submissionId);
                finalResult.setSubmitStatus("AC");
                finalResult.setFailIndex(0);
            }
            finalResult.setSubmitRecordId(submissionId);
            finalResult.setCode(submitRecord.getSubmitContent());
            finalResult.setCreateTime(LocalDateTime.now());
            judgeRecordMapper.insert(finalResult);
            // 发送结果
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY, finalResult.getJudgeRecordId());
            channel.basicAck(deliveryTag, false);


            log.info("判题完成, submissionId: {}, 通过: {}/{}", submissionId, passedCount, totalCount);

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
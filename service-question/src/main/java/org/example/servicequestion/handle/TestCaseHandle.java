package org.example.servicequestion.handle;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.TestMessage;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Component
@Slf4j
public class TestCaseHandle implements MessageHandler {
    @Autowired
    private TestCaseMapper testCaseMapper;
    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public String getRoutingKey() {
        return MqContexts.Question_TESTCASE_ROUTING_KEY;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String messageBody, Channel channel, Message amqpMessage) throws IOException {
        log.info("===== 开始处理测试用例消息 =====");
        log.info("消息体: {}", messageBody);

        try {
            // 解析消息
            List<TestMessage> messages = objectMapper.readValue(messageBody, new TypeReference<List<TestMessage>>() {});
            log.info("解析到 {} 个测试用例", messages.size());

            if (messages.isEmpty()) {
                log.warn("没有解析到任何测试用例");
                channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
                return;
            }
            Set<String> testset=new HashSet<>();
            List<TestCase> testcases=testCaseMapper.selectList(new QueryWrapper<TestCase>().eq("question_id",messages.getFirst().getQuestionId()));
            for (TestCase testcase:testcases){
                testset.add(DigestUtils.md5DigestAsHex((Arrays.toString(testcase.getInputData().getBytes()) +testcase.getExpectedOutput()).getBytes()));
            }

            int successCount = 0;
            for (int i = 0; i < messages.size(); i++) {
                TestMessage testMessage = messages.get(i);
                log.info("处理第 {} 个测试用例: {}", i + 1, testMessage);

                TestCase testCase = new TestCase();
                testCase.setQuestionId(testMessage.getQuestionId());
                testCase.setInputData(testMessage.getInputData());
                testCase.setExpectedOutput(testMessage.getExpectedOutput());
                testCase.setTimeLimit(testMessage.getTimeLimit());
                testCase.setMemoryLimit(testMessage.getMemoryLimit());
                testCase.setCreateUserId(testMessage.getCreateUserId());
                testCase.setScoreWeight(testMessage.getScoreWeight());
                testCase.setSortOrder(testMessage.getSortOrder());
                testCase.setCreateTime(LocalDateTime.now());
                testCase.setUpdateTime(LocalDateTime.now());

                log.info("准备插入: questionId={}, inputData={}",
                        testCase.getQuestionId(), testCase.getInputData());
                int result=0;
                if(!testset.contains(DigestUtils.md5DigestAsHex((Arrays.toString(testCase.getInputData().getBytes()) +testCase.getExpectedOutput()).getBytes()))){
                 result = testCaseMapper.insert(testCase);
                testset.add(DigestUtils.md5DigestAsHex((Arrays.toString(testCase.getInputData().getBytes()) +testCase.getExpectedOutput()).getBytes()));}
                log.info("插入结果影响行数: {}", result);

                if (result > 0) {
                    successCount++;
                    log.info("✅ 第 {} 个测试用例插入成功, id={}", i + 1, testCase.getCaseId());
                } else {
                    log.error("❌ 第 {} 个测试用例插入失败", i + 1);
                }
            }

            log.info("成功插入 {} 个测试用例", successCount);

            // 手动确认消息
            channel.basicAck(amqpMessage.getMessageProperties().getDeliveryTag(), false);
            questionMapper.updateAiStatusToSuccess(messages.getFirst().getQuestionId());
            log.info("===== 消息处理完成并确认 =====");

        } catch (Exception e) {

            log.error("处理测试用例消息失败: {}", e.getMessage(), e);
            // 拒绝消息，不重新入队

             channel.basicNack(amqpMessage.getMessageProperties().getDeliveryTag(), false, false);

            throw new RuntimeException("处理测试用例失败", e);
        }
    }
}
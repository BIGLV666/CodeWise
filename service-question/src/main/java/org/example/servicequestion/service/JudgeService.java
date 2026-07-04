package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.segments.MergeSegments;
import lombok.NonNull;
import org.example.serviceapi.dto.JudgeMqDto;
import org.example.serviceapi.dto.JudgeResultDto;
import org.example.serviceapi.dto.TestMessage;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqConfig;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JudgeService {
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private TestCaseMapper  testCaseMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    public Long judge(String code,String language,Long questionId){
        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitContent(code);
        submitRecord.setLanguage(language);
        submitRecord.setSubmitTime(LocalDateTime.now());
        submitRecord.setJudgeStatus("pending"); //pending/failue/success
        submitRecord.setQuestionId(questionId);
        submitRecord.setUserId(UserContext.getUserId());
        submitRecordMapper.insert(submitRecord);

        rabbitTemplate.convertAndSend(
                MqContexts.JUDGE_EXCHANGE,
                MqContexts.JUDGE_ROUTING_KEY,
                submitRecord.getSubmitRecordId()
        );

        return submitRecord.getSubmitRecordId();
    }


    public String debug(DebugDto debugDto){
        String uuid = UUID.randomUUID().toString();
        debugDto.setUserId(UserContext.getUserId());
        redisTemplate.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY,uuid,debugDto);
        rabbitTemplate.convertAndSend(
                MqContexts.JUDGE_EXCHANGE,
                MqContexts.JUDGE_DEBUG_ROUTING_KEY,
                uuid
        );
        return uuid;
    }


    private static @NonNull List<TestMessage> getTestMessages(List<TestCase> list) {
        List<TestMessage>testMessages=new ArrayList<>();
        for(TestCase testCase: list){
            TestMessage testMessage=new TestMessage();
            testMessage.setInputData(testCase.getInputData());
            testMessage.setExpectedOutput(testCase.getExpectedOutput());
            testMessage.setMemoryLimit(testCase.getMemoryLimit());
            testMessage.setScoreWeight(testCase.getScoreWeight());
            testMessage.setSortOrder(testCase.getSortOrder());
            testMessages.add(testMessage);
        }
        return testMessages;
    }
}

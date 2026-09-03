package org.example.servicequestion.service;

import lombok.NonNull;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.TestMessage;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class JudgeService {
    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private OutboxProPublisher outboxPublisher;

    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    /**
     * 提交判题：提交记录落库与判题请求事件在同一事务内写入（事务性 Outbox），
     * 事务提交后由 Relay 异步投递到判题交换机，避免「DB 已提交但消息丢失」。
     */
    @Transactional
    public Long judge(GetCodeDto getCodeDto) {

        String code = getCodeDto.getCode();
        String language = getCodeDto.getLanguage();
        Long questionId = getCodeDto.getQuestionId();
        String title=getCodeDto.getQuestionTitle();
        if(questionId == null) {
            throw new IllegalArgumentException("题目不存在");
        }

        String submitScene = getType(getCodeDto.getSubmitScene());
        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitContent(code);
        submitRecord.setLanguage(language);
        submitRecord.setQuestionTitle(title);
        submitRecord.setSubmitTime(LocalDateTime.now());
        submitRecord.setJudgeStatus("pending"); //pending/failue/success
        submitRecord.setQuestionId(questionId);
        submitRecord.setUserId(UserContext.getUserId());
        submitRecord.setSubmitScene(submitScene);
        submitRecordMapper.insert(submitRecord);

        // 判题请求改走事务性 Outbox：payload 只放 submitRecordId（小字段引用），
        // 路由（judge.exchange/judge.routing）在 OutboxEventRouteConfig 登记
        outboxPublisher.publish(EventTypes.JUDGE_SUBMIT_REQUEST, submitRecord.getSubmitRecordId());

        return submitRecord.getSubmitRecordId();
    }


    private String getType(String type){
         return switch (type){
             case "REVIEW" -> "REVIEW";
             case "NORMAL" -> "NORMAL";
             case  "PLAN" -> "PLAN";
             default -> throw new IllegalArgumentException("不支持的提交类型");
         };
    }


    /**
     * 调试判题：Redis 任务写入先行，Outbox 事件随后在同一事务内登记，
     * 事务提交后由 Relay 投递调试路由键，保证任务数据与消息的一致性。
     */
    @Transactional
    public String debug(DebugDto debugDto){
        String uuid = UUID.randomUUID().toString();
        debugDto.setUserId(UserContext.getUserId());
        redisTemplate.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY,uuid,debugDto);
        outboxPublisher.publish(EventTypes.JUDGE_DEBUG_REQUEST, uuid);
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

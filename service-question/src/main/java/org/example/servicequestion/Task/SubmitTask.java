package org.example.servicequestion.Task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.NonNull;
import org.example.serviceapi.dto.judge.JudgeMqDto;
import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.serviceapi.dto.question.TestMessage;
import org.example.servicecommon.config.MqContexts;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.example.servicequestion.service.JudgeService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Async
public class SubmitTask {
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    // 超时时间（分钟）
    private static final int TIMEOUT_MINUTES = 5;
    @Scheduled(cron = "0 */5 * * * *")
    public void submitTask(){
        List<org.example.servicequestion.entry.SubmitRecord>submitRecords=submitRecordMapper.selectList(new QueryWrapper<SubmitRecord>().eq("judge_status","pending"));
        for(org.example.servicequestion.entry.SubmitRecord submitRecord:submitRecords){
            if(submitRecord.getJudgeStatus().equals("pending")&&isTimeout(submitRecord.getCreateTime())){
            rabbitTemplate.convertAndSend(
                    MqContexts.JUDGE_EXCHANGE,
                    MqContexts.JUDGE_ROUTING_KEY,
                    submitRecord.getSubmitRecordId()
            );}
        }
    }
    /**
     * 判断是否超时
     */
    private boolean isTimeout(LocalDateTime createTime) {
        if (createTime == null) {
            return true;
        }
        return Duration.between(createTime, LocalDateTime.now()).toMinutes() > TIMEOUT_MINUTES;
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

package org.example.servicejudge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.GetDebugTestDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Mq.MessageHandler;
import org.example.servicejudge.Util.BuildResult;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.Question;
import org.example.servicejudge.judge.JudgeService;
import org.example.servicejudge.mapper.QuestionMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class DebugServiceHandle implements MessageHandler {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private JudgeService judgeService;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public String getRoutingKey() {
        return MqContexts.JUDGE_DEBUG_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        Long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String uuid=objectMapper.readValue(message,String.class);
        try{

            if(uuid.isEmpty()){
                log.info("uuid is empty{}",uuid);
                throw new RuntimeException("未找到该提交");
            }
//            if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.JUDGE_SUCCESS_KEY + uuid, "pending", 5, TimeUnit.MINUTES))){
//                log.info("uuid{}已经处理",uuid);
//                channel.basicAck(deliveryTag,false);
//            }
            DebugDto debugDto=(DebugDto) redisTemplate.opsForHash().get(RedisContext.JUDGE_DEBUG_KEY,uuid);
            if(debugDto==null){
                log.info("未找到该提交");
                throw new RuntimeException("未找到该提交");
            }
            Question question=questionMapper.selectById(debugDto.getQuestionId());
            if(question==null){
                log.info("该题目不存在");
                throw new RuntimeException("该题目不存在");
            }
            List<GetDebugTestDto>tests=debugDto.getTests();
            if(question.getSampleInput()!=null&&question.getSampleOutput()!=null){
                GetDebugTestDto getDebugTestDto=new GetDebugTestDto();
                getDebugTestDto.setInput(question.getSampleInput());
                getDebugTestDto.setOutput(question.getSampleOutput());
                tests.add(getDebugTestDto);
                debugDto.setTests(tests);
            }
            int i=0;
            List<JudgeReturnRecordDto>res=new ArrayList<>();
            for(GetDebugTestDto getDebugTestDto:tests){
                i++;
                JudgeReturnDto  judgeReturnDto=judgeService.executeCode(debugDto.getCode(),debugDto.getLanguage(),getDebugTestDto.getInput());
                JudgeRecord judgeRecord= BuildResult.buildResult(judgeReturnDto,null,getDebugTestDto.getInput(),getDebugTestDto.getOutput(),i+1,debugDto.getLanguage());
                JudgeReturnRecordDto judgeReturnRecordDto=ToJudgeReturnRecordDto(judgeRecord);
                judgeReturnRecordDto.setUserId(debugDto.getUserId());
                res.add(judgeReturnRecordDto);
            }
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_DEBUG_ROUTING_KEY,
                    uuid
            );
           redisTemplate.opsForHash().put(RedisContext.JUDGE_RESULT_KEY,uuid,res);
           redisTemplate.opsForValue().setIfAbsent(RedisContext.JUDGE_SUCCESS_KEY + uuid, "success", 5, TimeUnit.MINUTES);
           channel.basicAck(deliveryTag,false);
        }catch(Exception e){
            JudgeReturnDto judgeReturnDto=new JudgeReturnDto();
            judgeReturnDto.setErrorMsg("内部系统错误，请稍后重试");
            redisTemplate.opsForHash().put(RedisContext.JUDGE_RESULT_KEY,uuid,judgeReturnDto);
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_DEBUG_ROUTING_KEY,
                    uuid
            );
            channel.basicNack(deliveryTag,false,false);
            throw new RuntimeException(e);
        }
    }
    private JudgeReturnRecordDto ToJudgeReturnRecordDto(JudgeRecord judgeRecord){
        JudgeReturnRecordDto judgeReturnRecordDto = new JudgeReturnRecordDto();
        judgeReturnRecordDto.setLog(judgeRecord.getLog());
        judgeReturnRecordDto.setErrorMsg(judgeRecord.getErrorMsg());
        judgeReturnRecordDto.setExpectedOutput(judgeRecord.getExpectedOutput());
        judgeReturnRecordDto.setFailIndex(judgeRecord.getFailIndex());
        judgeReturnRecordDto.setMemoryUsed(judgeRecord.getMemoryUsed());
        judgeReturnRecordDto.setTimeUsed(judgeRecord.getTimeUsed());
        judgeReturnRecordDto.setInputData(judgeRecord.getInputData());
        judgeReturnRecordDto.setSubmitStatus(judgeRecord.getSubmitStatus());
        judgeReturnRecordDto.setUserOutput(judgeRecord.getUserOutput());
        return judgeReturnRecordDto;
    }
}

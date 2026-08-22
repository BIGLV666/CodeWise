package org.example.servicejudge.Mq.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.GetDebugTestDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Util.BuildResult;
import org.example.servicejudge.Util.CodeBuild;
import org.example.servicejudge.entry.FunctionConfig;
import org.example.servicejudge.entry.FunctionTestCase;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.Question;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.functionsService.Java;
import org.example.servicejudge.judge.JudgeService;
import org.example.servicejudge.mapper.FunctionConfigMapper;
import org.example.servicejudge.mapper.QuestionMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调试判题消息处理器，负责 Redis 中调试任务的读取、执行和结果回写。
 *
 * <p>本类不再承担 Channel/ACK 职责（由消费者负责）。业务失败时把错误结果
 * 写回 Redis 并回调题目服务后正常返回（消费者 ACK），不进入重试链路；
 * 仅当错误处理本身抛出异常时才由消费者 nack 进死信。</p>
 */
@Service
@Slf4j
public class JudgeDebugHandler {

    private final RabbitTemplate rabbitTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final QuestionMapper questionMapper;
    private final JudgeService judgeService;
    private final FunctionConfigMapper functionConfigMapper;

    public JudgeDebugHandler(
            RabbitTemplate rabbitTemplate,
            RedisTemplate<String, Object> redisTemplate,
            QuestionMapper questionMapper,
            JudgeService judgeService,
            FunctionConfigMapper functionConfigMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
        this.questionMapper = questionMapper;
        this.judgeService = judgeService;
        this.functionConfigMapper = functionConfigMapper;
    }

    /**
     * 执行一个调试任务：Redis 幂等占位 -> 读取任务 -> 按题目类型判题 ->
     * 结果写回 Redis 并回调题目服务。业务异常被内部消化为 SYSTEM_ERROR 结果。
     *
     * @param uuid 调试任务 ID（Redis hash key）
     */
    public void handle(String uuid) {
        DebugDto debugDto = null;
        try {
            if (uuid == null || uuid.isEmpty()) {
                log.info("uuid is empty{}", uuid);
                throw new RuntimeException("未找到该提交");
            }
            Boolean firstConsume = redisTemplate.opsForValue()
                    .setIfAbsent(RedisContext.JUDGE_SUCCESS_KEY + uuid, "pending", 5, TimeUnit.MINUTES);
            if (Boolean.FALSE.equals(firstConsume)) {
                log.info("uuid{}已经处理", uuid);
                return;
            }
            debugDto = (DebugDto) redisTemplate.opsForHash().get(RedisContext.JUDGE_DEBUG_KEY, uuid);
            if (debugDto == null) {
                log.info("未找到该提交");
                throw new RuntimeException("未找到该提交");
            }
            Question question = questionMapper.selectById(debugDto.getQuestionId());
            if (question == null) {
                log.info("该题目不存在");
                throw new RuntimeException("该题目不存在");
            }
            List<GetDebugTestDto> tests = debugDto.getTests() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(debugDto.getTests());
            if (!Boolean.FALSE.equals(debugDto.getIncludeQuestionSample())
                    && question.getSampleInput() != null
                    && question.getSampleOutput() != null) {
                GetDebugTestDto getDebugTestDto = new GetDebugTestDto();
                getDebugTestDto.setInput(question.getSampleInput());
                getDebugTestDto.setOutput(question.getSampleOutput());
                tests.add(getDebugTestDto);
                debugDto.setTests(tests);
            }
            List<JudgeReturnRecordDto> res = question.getQuestionType() == QuestionType.FUNCTION
                    ? debugFunction(debugDto, tests)
                    : debugAcm(debugDto, tests);
            redisTemplate.opsForHash().put(RedisContext.JUDGE_RESULT_KEY, uuid, res);
            redisTemplate.opsForValue().set(
                    RedisContext.JUDGE_SUCCESS_KEY + uuid,
                    "success",
                    5,
                    TimeUnit.MINUTES
            );
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_DEBUG_ROUTING_KEY,
                    uuid
            );
        } catch (Exception e) {
            log.error("调试任务执行失败, taskId={}", uuid, e);
            JudgeReturnRecordDto errorResult = new JudgeReturnRecordDto();
            errorResult.setUserId(debugDto == null ? null : debugDto.getUserId());
            errorResult.setSubmitStatus("SYSTEM_ERROR");
            errorResult.setErrorMsg("内部系统错误: " + e.getMessage());
            redisTemplate.opsForHash().put(
                    RedisContext.JUDGE_RESULT_KEY,
                    uuid,
                    List.of(errorResult)
            );
            rabbitTemplate.convertAndSend(
                    MqContexts.Question_EXCHANGE,
                    MqContexts.QUESTION_DEBUG_ROUTING_KEY,
                    uuid
            );
        }
    }

    /**
     * 函数模式调试：生成 Main 调用器并批量执行全部用例。
     */
    List<JudgeReturnRecordDto> debugFunction(
            DebugDto debugDto,
            List<GetDebugTestDto> tests
    ) throws IOException {
        if (!"java".equalsIgnoreCase(debugDto.getLanguage())) {
            throw new IllegalArgumentException("函数模式暂时只支持 Java");
        }
        FunctionConfig functionConfig = functionConfigMapper.selectOne(
                new QueryWrapper<FunctionConfig>().eq("question_id", debugDto.getQuestionId())
        );
        if (functionConfig == null) {
            throw new IllegalStateException("函数模式题目配置不存在");
        }

        String mainCode = Java.ToMain(
                functionConfig.getParameterConfig(),
                functionConfig.getMethodName(),
                functionConfig.getOutputType()
        );
        String code = CodeBuild.build(
                debugDto.getCode(),
                functionConfig.getParameterConfig(),
                functionConfig.getOutputType()
        );
        List<JudgeRecord> judgeRecords = judgeService.batchDebugCode(
                code,
                mainCode,
                debugDto.getLanguage(),
                toFunctionTestDtos(debugDto.getQuestionId(), tests)
        );
        return toReturnRecords(judgeRecords, debugDto.getUserId());
    }

    /**
     * ACM 模式调试：逐用例独立运行（互不影响）。
     */
    private List<JudgeReturnRecordDto> debugAcm(
            DebugDto debugDto,
            List<GetDebugTestDto> tests
    ) {
        List<JudgeReturnRecordDto> results = new ArrayList<>();
        for (int index = 0; index < tests.size(); index++) {
            GetDebugTestDto test = tests.get(index);
            JudgeReturnDto judgeReturnDto = judgeService.executeCode(
                    debugDto.getCode(),
                    debugDto.getLanguage(),
                    test.getInput()
            );
            JudgeRecord judgeRecord = BuildResult.buildResult(
                    judgeReturnDto,
                    null,
                    test.getInput(),
                    test.getOutput(),
                    index + 1,
                    debugDto.getLanguage()
            );
            JudgeReturnRecordDto result = ToJudgeReturnRecordDto(judgeRecord);
            result.setUserId(debugDto.getUserId());
            results.add(result);
        }
        return results;
    }

    private List<TestDto> toFunctionTestDtos(Long questionId, List<GetDebugTestDto> tests) {
        List<TestDto> testDtos = new ArrayList<>(tests.size());
        for (GetDebugTestDto test : tests) {
            FunctionTestCase functionTestCase = FunctionTestCase.builder()
                    .questionId(questionId)
                    .inputData(test.getInput())
                    .expectedOutput(test.getOutput())
                    .build();
            testDtos.add(new TestDto(functionTestCase));
        }
        return testDtos;
    }

    private List<JudgeReturnRecordDto> toReturnRecords(List<JudgeRecord> records, Long userId) {
        List<JudgeReturnRecordDto> results = new ArrayList<>(records.size());
        for (JudgeRecord record : records) {
            JudgeReturnRecordDto result = ToJudgeReturnRecordDto(record);
            result.setUserId(userId);
            results.add(result);
        }
        return results;
    }

    private JudgeReturnRecordDto ToJudgeReturnRecordDto(JudgeRecord judgeRecord) {
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

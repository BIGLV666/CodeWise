package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.GetDebugTestDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.dto.FunctionTestCaseGenerateRequest;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.vo.FunctionTestCaseGenerationTaskVo;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class FunctionTestCaseGenerationService {

    private static final int DEFAULT_COUNT = 20;
    private static final int MAX_COUNT = 100;
    private static final int MAX_CODE_LENGTH = 100_000;
    private static final long TASK_TTL_MINUTES = 30;

    private final QuestionPermissionService permissionService;
    private final FunctionConfigMapper functionConfigMapper;
    private final FunctionRandomInputGenerator inputGenerator;
    private final FunctionCaseDataConverter caseDataConverter;
    private final FunctionQuestionParseService functionQuestionParseService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    public FunctionTestCaseGenerationService(
            QuestionPermissionService permissionService,
            FunctionConfigMapper functionConfigMapper,
            FunctionRandomInputGenerator inputGenerator,
            FunctionCaseDataConverter caseDataConverter,
            FunctionQuestionParseService functionQuestionParseService,
            RedisTemplate<String, Object> redisTemplate,
            RabbitTemplate rabbitTemplate
    ) {
        this.permissionService = permissionService;
        this.functionConfigMapper = functionConfigMapper;
        this.inputGenerator = inputGenerator;
        this.caseDataConverter = caseDataConverter;
        this.functionQuestionParseService = functionQuestionParseService;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    public FunctionTestCaseGenerationTaskVo start(FunctionTestCaseGenerateRequest request) {
        validateRequest(request);
        Long userId = UserContext.getUserId();
        Question question = permissionService.requireOwnerOrAdmin(request.getQuestionId(), userId);
        if (question.getQuestionType() != QuestionType.FUNCTION) {
            throw new BizException(QuestionErrorCode.NOT_FUNCTION_QUESTION);
        }

        FunctionConfig config = functionConfigMapper.selectOne(
                new LambdaQueryWrapper<FunctionConfig>()
                        .eq(FunctionConfig::getQuestionId, request.getQuestionId())
                        .last("LIMIT 1")
        );
        if (config == null) {
            throw new BizException(QuestionErrorCode.FUNCTION_CONFIG_NOT_FOUND);
        }

        int count = request.getCount() == null ? DEFAULT_COUNT : request.getCount();
        long seed = request.getSeed() == null ? System.nanoTime() : request.getSeed();
        List<String> generatedInputs = inputGenerator.generate(config.getParameterConfig(), count, seed)
                .stream()
                .map(input -> caseDataConverter.normalizeInput(input, config.getParameterConfig()))
                .toList();

        String taskId = UUID.randomUUID().toString();
        FunctionTestCaseGenerationTaskVo task = FunctionTestCaseGenerationTaskVo.builder()
                .taskId(taskId)
                .questionId(request.getQuestionId())
                .userId(userId)
                .status("PENDING")
                .requestedCount(count)
                .generatedCount(0)
                .seed(seed)
                .createTime(LocalDateTime.now())
                .build();

        DebugDto debugDto = new DebugDto();
        debugDto.setUserId(userId);
        debugDto.setCode(request.getStandardAnswer());
        debugDto.setLanguage("java");
        debugDto.setQuestionId(request.getQuestionId());
        debugDto.setIncludeQuestionSample(false);
        debugDto.setTests(toDebugTests(generatedInputs));

        redisTemplate.opsForValue().set(taskKey(taskId), task, TASK_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY, taskId, debugDto);
        rabbitTemplate.convertAndSend(
                MqContexts.JUDGE_EXCHANGE,
                MqContexts.JUDGE_DEBUG_ROUTING_KEY,
                taskId
        );
        log.info(
                "随机测试生成任务已提交, taskId={}, questionId={}, count={}, seed={}, userId={}",
                taskId,
                request.getQuestionId(),
                count,
                seed,
                userId
        );
        return task;
    }

    public FunctionTestCaseGenerationTaskVo getTask(String taskId) {
        FunctionTestCaseGenerationTaskVo task = loadTask(taskId);
        Long userId = UserContext.getUserId();
        if (userId == null || !userId.equals(task.getUserId())) {
            throw new BizException(CommonErrorCode.NO_PERMISSION);
        }
        permissionService.requireOwnerOrAdmin(task.getQuestionId(), userId);
        return task;
    }

    public boolean isGenerationTask(String taskId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(taskKey(taskId)));
    }

    public void complete(String taskId, List<JudgeReturnRecordDto> results) {
        FunctionTestCaseGenerationTaskVo task = loadTask(taskId);
        permissionService.requireOwnerOrAdmin(task.getQuestionId(), task.getUserId());
        if (results == null || results.size() != task.getRequestedCount()) {
            throw new BizException(QuestionErrorCode.GENERATE_RESULT_INCOMPLETE);
        }

        List<FunctionTestCaseDto> testCases = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            JudgeReturnRecordDto result = results.get(index);
            if (result == null || !("AC".equals(result.getSubmitStatus())
                    || "WA".equals(result.getSubmitStatus()))) {
                String error = result == null ? "无执行结果" : result.getErrorMsg();
                log.warn("标准答案在随机输入 {} 执行失败, taskId={}, error={}", index + 1, taskId, error);
                throw new BizException(QuestionErrorCode.GENERATE_STANDARD_ANSWER_FAILED);
            }
            FunctionTestCaseDto testCase = new FunctionTestCaseDto();
            testCase.setInput(result.getInputData());
            testCase.setOutput(result.getUserOutput());
            testCases.add(testCase);
        }

        int inserted = functionQuestionParseService.insertTestCases(task.getQuestionId(), testCases);
        task.setStatus("SUCCESS");
        task.setGeneratedCount(inserted);
        task.setFinishTime(LocalDateTime.now());
        task.setErrorMessage(null);
        saveTask(task);
        log.info(
                "随机测试生成成功, taskId={}, questionId={}, generatedCount={}",
                taskId,
                task.getQuestionId(),
                inserted
        );
    }

    public void fail(String taskId, String message) {
        FunctionTestCaseGenerationTaskVo task = loadTask(taskId);
        task.setStatus("FAILED");
        task.setErrorMessage(message == null ? "生成失败" : message);
        task.setFinishTime(LocalDateTime.now());
        saveTask(task);
        log.error(
                "随机测试生成失败, taskId={}, questionId={}, error={}",
                taskId,
                task.getQuestionId(),
                task.getErrorMessage()
        );
    }

    private void validateRequest(FunctionTestCaseGenerateRequest request) {
        if (request == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if (request.getQuestionId() == null) {
            throw new BizException(QuestionErrorCode.QUESTION_ID_REQUIRED);
        }
        if (request.getStandardAnswer() == null || request.getStandardAnswer().isBlank()) {
            throw new BizException(QuestionErrorCode.STANDARD_ANSWER_REQUIRED);
        }
        if (request.getStandardAnswer().length() > MAX_CODE_LENGTH) {
            throw new BizException(QuestionErrorCode.STANDARD_ANSWER_TOO_LONG, MAX_CODE_LENGTH);
        }
        if (request.getLanguage() != null && !"java".equalsIgnoreCase(request.getLanguage())) {
            throw new BizException(QuestionErrorCode.GENERATE_LANGUAGE_UNSUPPORTED);
        }
        int count = request.getCount() == null ? DEFAULT_COUNT : request.getCount();
        if (count < 1 || count > MAX_COUNT) {
            throw new BizException(QuestionErrorCode.GENERATE_COUNT_INVALID, 1, MAX_COUNT);
        }
    }

    private List<GetDebugTestDto> toDebugTests(List<String> inputs) {
        List<GetDebugTestDto> tests = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            GetDebugTestDto test = new GetDebugTestDto();
            test.setInput(input);
            test.setOutput("");
            tests.add(test);
        }
        return tests;
    }

    private FunctionTestCaseGenerationTaskVo loadTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BizException(QuestionErrorCode.TASK_ID_INVALID);
        }
        Object value = redisTemplate.opsForValue().get(taskKey(taskId));
        if (!(value instanceof FunctionTestCaseGenerationTaskVo task)) {
            throw new BizException(QuestionErrorCode.GENERATION_TASK_NOT_FOUND);
        }
        return task;
    }

    private void saveTask(FunctionTestCaseGenerationTaskVo task) {
        redisTemplate.opsForValue().set(
                taskKey(task.getTaskId()),
                task,
                TASK_TTL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private String taskKey(String taskId) {
        return RedisContext.FUNCTION_CASE_GENERATION_TASK_KEY + taskId;
    }
}

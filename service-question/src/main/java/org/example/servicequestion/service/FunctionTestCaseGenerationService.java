package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
            throw new IllegalArgumentException("仅函数模式题目支持随机测试生成");
        }

        FunctionConfig config = functionConfigMapper.selectOne(
                new LambdaQueryWrapper<FunctionConfig>()
                        .eq(FunctionConfig::getQuestionId, request.getQuestionId())
                        .last("LIMIT 1")
        );
        if (config == null) {
            throw new IllegalStateException("函数配置不存在");
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
            throw new SecurityException("无权查看该生成任务");
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
            throw new IllegalStateException("标准答案返回的测试结果数量不完整");
        }

        List<FunctionTestCaseDto> testCases = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            JudgeReturnRecordDto result = results.get(index);
            if (result == null || !("AC".equals(result.getSubmitStatus())
                    || "WA".equals(result.getSubmitStatus()))) {
                String error = result == null ? "无执行结果" : result.getErrorMsg();
                throw new IllegalStateException("标准答案在随机输入 " + (index + 1) + " 执行失败: " + error);
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
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.getQuestionId() == null) {
            throw new IllegalArgumentException("题目 ID 不能为空");
        }
        if (request.getStandardAnswer() == null || request.getStandardAnswer().isBlank()) {
            throw new IllegalArgumentException("标准答案不能为空");
        }
        if (request.getStandardAnswer().length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("标准答案长度不能超过 100000 个字符");
        }
        if (request.getLanguage() != null && !"java".equalsIgnoreCase(request.getLanguage())) {
            throw new IllegalArgumentException("函数模式随机生成暂时只支持 Java");
        }
        int count = request.getCount() == null ? DEFAULT_COUNT : request.getCount();
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("生成数量必须在 1 到 100 之间");
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
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        Object value = redisTemplate.opsForValue().get(taskKey(taskId));
        if (!(value instanceof FunctionTestCaseGenerationTaskVo task)) {
            throw new IllegalArgumentException("生成任务不存在或已过期");
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

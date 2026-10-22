package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionDescriptionRequest;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
import org.example.serviceapi.dto.ai.FunctionParameterRange;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.AiDescriptionFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.FunctionBatchImportItem;
import org.example.servicequestion.dto.FunctionBatchImportRequest;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseGenerateRequest;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.vo.FunctionBatchImportItemVo;
import org.example.servicequestion.vo.FunctionBatchImportTaskVo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 批量导入函数题：把平台外已验证过的题目成批搬进题库。
 *
 * <p>逐条流程：题面（缺省时用解法源码让 service-ai 原创重写）→ 建题与函数配置 →
 * 提交隐藏用例生成（标准答案＝本条解法，随机输入由平台生成、经判题沙箱产出期望输出）。</p>
 *
 * <p>三条刻意的设计：</p>
 * <ol>
 *   <li><b>异步 + 逐条隔离</b>：题面重写与判题都是慢操作，整批放在一个 HTTP 请求里会被
 *       网关/nginx 超时切断；单条失败只标记该条，不影响其他条目。</li>
 *   <li><b>身份显式传递</b>：任务跑在线程池里，{@link UserContext} 不会自动跟过去
 *       （ThreadLocal 不跨线程），所以 userId 在建任务时捕获、在 worker 里重新写入，
 *       否则建题会写出空的 createUserId 并触发数据库非空约束。</li>
 *   <li><b>不在服务端等用例生成结果</b>：用例由判题异步产出，任务只负责提交，
 *       调用方按返回的 generationTaskId 查询既有生成任务接口。</li>
 * </ol>
 */
@Service
@Slf4j
public class FunctionBatchImportService {

    private static final int MAX_ITEMS = 50;
    private static final int DEFAULT_CASE_COUNT = 20;
    private static final int MAX_CASE_COUNT = 100;
    private static final long TASK_TTL_HOURS = 6;
    private static final String DEFAULT_SOURCE = "原创改编";

    private final QuestionPermissionService permissionService;
    private final FunctionQuestionParseService questionParseService;
    private final FunctionTestCaseGenerationService generationService;
    private final FunctionParameterRangeMerger parameterRangeMerger;
    private final AiDescriptionFeignClient aiDescriptionFeignClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TaskExecutor taskExecutor;
    private final String internalToken;

    public FunctionBatchImportService(
            QuestionPermissionService permissionService,
            FunctionQuestionParseService questionParseService,
            FunctionTestCaseGenerationService generationService,
            FunctionParameterRangeMerger parameterRangeMerger,
            AiDescriptionFeignClient aiDescriptionFeignClient,
            RedisTemplate<String, Object> redisTemplate,
            @Qualifier("service-question") TaskExecutor taskExecutor,
            @Value("${codewise.internal-token}") String internalToken
    ) {
        this.permissionService = permissionService;
        this.questionParseService = questionParseService;
        this.generationService = generationService;
        this.parameterRangeMerger = parameterRangeMerger;
        this.aiDescriptionFeignClient = aiDescriptionFeignClient;
        this.redisTemplate = redisTemplate;
        this.taskExecutor = taskExecutor;
        this.internalToken = internalToken;
    }

    public FunctionBatchImportTaskVo start(FunctionBatchImportRequest request) {
        Long userId = UserContext.getUserId();
        permissionService.requireAdmin(userId);
        List<FunctionBatchImportItem> items = validateAndCopyItems(request);

        String taskId = UUID.randomUUID().toString();
        List<FunctionBatchImportItemVo> itemStates = new ArrayList<>(items.size());
        for (FunctionBatchImportItem item : items) {
            itemStates.add(FunctionBatchImportItemVo.builder()
                    .title(item.getTitle())
                    .status("PENDING")
                    .descriptionGenerated(false)
                    .build());
        }

        FunctionBatchImportTaskVo task = FunctionBatchImportTaskVo.builder()
                .taskId(taskId)
                .userId(userId)
                .status("RUNNING")
                .total(items.size())
                .submitted(0)
                .failed(0)
                .createTime(LocalDateTime.now())
                .items(itemStates)
                .build();
        save(task);

        taskExecutor.execute(() -> execute(taskId, request, items));
        return task;
    }

    public FunctionBatchImportTaskVo getTask(String taskId) {
        permissionService.requireAdmin(UserContext.getUserId());
        return load(taskId);
    }

    /**
     * 后台执行：逐条处理并落状态快照，任何单条异常都只影响该条。
     */
    public void execute(String taskId, FunctionBatchImportRequest request, List<FunctionBatchImportItem> items) {
        FunctionBatchImportTaskVo task = load(taskId);
        UserContext.setUserId(task.getUserId());
        try {
            int submitted = 0;
            int failed = 0;
            for (int index = 0; index < items.size(); index++) {
                FunctionBatchImportItemVo state = task.getItems().get(index);
                FunctionBatchImportItem item = items.get(index);
                state.setStatus("RUNNING");
                save(task);
                try {
                    importOne(item, request, index, state);
                    state.setStatus("GENERATING");
                    submitted++;
                } catch (BizException exception) {
                    state.setStatus("FAILED");
                    state.setErrorMessage(exception.getMessage());
                    failed++;
                    log.warn("批量导入条目失败, taskId={}, index={}, title={}, error={}",
                            taskId, index + 1, item.getTitle(), exception.getMessage());
                } catch (Exception exception) {
                    state.setStatus("FAILED");
                    state.setErrorMessage(QuestionErrorCode.QUESTION_IMPORT_FAILED.getMessage());
                    failed++;
                    log.error("批量导入条目异常, taskId={}, index={}, title={}",
                            taskId, index + 1, item.getTitle(), exception);
                }
                task.setSubmitted(submitted);
                task.setFailed(failed);
                save(task);
            }

            task.setStatus(failed == 0 ? "SUCCESS" : (submitted == 0 ? "FAILED" : "PARTIAL"));
            task.setFinishTime(LocalDateTime.now());
            save(task);
        } finally {
            // 线程池里的线程会被复用，必须清掉身份，避免串到下一个任务
            UserContext.clear();
        }
    }

    private void importOne(
            FunctionBatchImportItem item,
            FunctionBatchImportRequest request,
            int index,
            FunctionBatchImportItemVo state
    ) {
        boolean needDescription = item.getDescription() == null || item.getDescription().isBlank();
        String description = item.getDescription();
        String inputDesc = item.getInputDesc();
        String outputDesc = item.getOutputDesc();
        String hint = item.getHint();
        String tags = item.getTags();
        Integer difficulty = item.getDifficulty();
        List<FunctionParameterRange> parameterRanges = item.getParameterRanges();

        if (needDescription) {
            if (!Boolean.FALSE.equals(request.getAllowDescriptionGeneration())) {
                FunctionDescriptionResponse generated = generateDescription(item);
                description = generated.getDescription();
                inputDesc = firstNonBlank(inputDesc, generated.getInputDesc());
                outputDesc = firstNonBlank(outputDesc, generated.getOutputDesc());
                hint = firstNonBlank(hint, generated.getHint());
                tags = firstNonBlank(tags, generated.getTags());
                difficulty = difficulty == null ? generated.getDifficulty() : difficulty;
                if (parameterRanges == null || parameterRanges.isEmpty()) {
                    parameterRanges = generated.getParameterRanges();
                }
                state.setDescriptionGenerated(true);
            } else {
                throw new BizException(QuestionErrorCode.QUESTION_CONTENT_REQUIRED);
            }
        }

        FunctionDto functionDto = new FunctionDto();
        functionDto.setTitle(item.getTitle().trim());
        functionDto.setDescription(description);
        functionDto.setInputDesc(inputDesc);
        functionDto.setOutputDesc(outputDesc);
        functionDto.setHint(hint);
        functionDto.setTags(tags);
        functionDto.setDifficulty(difficulty == null ? 1 : difficulty);
        functionDto.setSource(firstNonBlank(item.getSource(), request.getDefaultSource(), DEFAULT_SOURCE));
        functionDto.setTimeLimit(item.getTimeLimit() == null ? 2000 : item.getTimeLimit());
        functionDto.setMemoryLimit(item.getMemoryLimit() == null ? 256 : item.getMemoryLimit());
        functionDto.setClassName(firstNonBlank(item.getClassName(), "Solution"));
        functionDto.setMethodName(item.getMethodName().trim());
        functionDto.setParameterConfig(item.getParameterConfig());
        functionDto.setOutputType(item.getOutputType().trim());
        // 样例不用外部文本：建题时先留空，等用例生成后由「提升样例」接口用真实执行结果回填
        functionDto.setSamples(List.of());

        // 先建为私密：用例是异步生成的，只有回填成功后才由调用方发布（见 promoteSamples）
        Long questionId = questionParseService.insert(functionDto, 3);
        state.setQuestionId(questionId);

        FunctionTestCaseGenerateRequest generateRequest = new FunctionTestCaseGenerateRequest();
        generateRequest.setQuestionId(questionId);
        generateRequest.setLanguage("java");
        generateRequest.setStandardAnswer(item.getSolutionCode());
        generateRequest.setCount(caseCountOf(item, request));
        generateRequest.setSeed(seedOf(item, request, index));
        // 取值域只影响随机采样：与落库参数配置合并后作为覆盖传入，题面与数据才对得上
        generateRequest.setParameterConfigOverride(
                parameterRangeMerger.merge(item.getParameterConfig(), parameterRanges)
        );
        String generationTaskId = generationService.start(generateRequest).getTaskId();
        state.setGenerationTaskId(generationTaskId);
    }

    private FunctionDescriptionResponse generateDescription(FunctionBatchImportItem item) {
        FunctionDescriptionRequest request = new FunctionDescriptionRequest();
        request.setTitle(item.getTitle());
        request.setClassName(firstNonBlank(item.getClassName(), "Solution"));
        request.setMethodName(item.getMethodName());
        request.setParameterConfig(item.getParameterConfig());
        request.setOutputType(item.getOutputType());
        request.setDifficulty(item.getDifficulty());
        request.setTags(item.getTags());
        request.setSolutionCode(item.getSolutionCode());
        request.setReferenceCode(item.getReferenceCode());
        request.setNotes(item.getNotes());

        Result<FunctionDescriptionResponse> response;
        try {
            response = aiDescriptionFeignClient.generateFunctionDescription(internalToken, request);
        } catch (Exception exception) {
            // 下游原始报错只进日志，不透传文案
            log.warn("题面重写调用失败, title={}", item.getTitle(), exception);
            throw new BizException(QuestionErrorCode.DESCRIPTION_GENERATE_FAILED, exception);
        }
        if (response == null || response.getCode() != 200 || response.getData() == null) {
            String downstream = response == null ? "null" : response.getMessage();
            log.warn("题面重写下游失败, title={}, message={}", item.getTitle(), downstream);
            throw new BizException(QuestionErrorCode.DESCRIPTION_GENERATE_FAILED);
        }
        return response.getData();
    }

    private List<FunctionBatchImportItem> validateAndCopyItems(FunctionBatchImportRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BizException(QuestionErrorCode.BATCH_IMPORT_ITEMS_REQUIRED);
        }
        if (request.getItems().size() > MAX_ITEMS) {
            throw new BizException(QuestionErrorCode.BATCH_IMPORT_LIMIT_EXCEEDED, MAX_ITEMS);
        }
        List<FunctionBatchImportItem> items = new ArrayList<>(request.getItems().size());
        for (int index = 0; index < request.getItems().size(); index++) {
            FunctionBatchImportItem item = request.getItems().get(index);
            if (item == null) {
                throw new BizException(QuestionErrorCode.BATCH_IMPORT_ITEM_INCOMPLETE, index + 1);
            }
            if (isBlank(item.getTitle())
                    || isBlank(item.getMethodName())
                    || isBlank(item.getParameterConfig())
                    || isBlank(item.getOutputType())
                    || isBlank(item.getSolutionCode())) {
                throw new BizException(QuestionErrorCode.BATCH_IMPORT_ITEM_INCOMPLETE, index + 1);
            }
            items.add(item);
        }
        return items;
    }

    private int caseCountOf(FunctionBatchImportItem item, FunctionBatchImportRequest request) {
        Integer count = item.getCaseCount() != null ? item.getCaseCount() : request.getCaseCount();
        return count == null ? DEFAULT_CASE_COUNT : count;
    }

    /** 种子按「请求级种子 + 条目序号」派生：同批可复现，条目之间又互不相同。 */
    private long seedOf(FunctionBatchImportItem item, FunctionBatchImportRequest request, int index) {
        if (item.getSeed() != null) {
            return item.getSeed();
        }
        long base = request.getSeed() == null ? System.nanoTime() : request.getSeed();
        return base + index;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private FunctionBatchImportTaskVo load(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BizException(QuestionErrorCode.TASK_ID_INVALID);
        }
        Object value = redisTemplate.opsForValue().get(taskKey(taskId));
        if (!(value instanceof FunctionBatchImportTaskVo task)) {
            throw new BizException(QuestionErrorCode.GENERATION_TASK_NOT_FOUND);
        }
        return task;
    }

    private void save(FunctionBatchImportTaskVo task) {
        redisTemplate.opsForValue().set(
                taskKey(task.getTaskId()),
                task,
                TASK_TTL_HOURS,
                TimeUnit.HOURS
        );
    }

    private String taskKey(String taskId) {
        return RedisContext.FUNCTION_BATCH_IMPORT_TASK_KEY + taskId;
    }
}

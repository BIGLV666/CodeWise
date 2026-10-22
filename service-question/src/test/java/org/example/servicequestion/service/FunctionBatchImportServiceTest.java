package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
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
import org.example.servicequestion.vo.FunctionTestCaseGenerationTaskVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionBatchImportServiceTest {

    private static final Long ADMIN_ID = 1L;

    @Mock
    private QuestionPermissionService permissionService;
    @Mock
    private FunctionQuestionParseService questionParseService;
    @Mock
    private FunctionTestCaseGenerationService generationService;
    @Mock
    private AiDescriptionFeignClient aiDescriptionFeignClient;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;

    /** 同步执行器：把后台任务放在测试线程里跑，断言才能确定。 */
    private final TaskExecutor synchronousExecutor = Runnable::run;

    private final Map<String, Object> redisStore = new ConcurrentHashMap<>();

    private final FunctionParameterRangeMerger rangeMerger =
            new FunctionParameterRangeMerger(new ObjectMapper());

    private FunctionBatchImportService service;

    @BeforeEach
    void setUp() {
        // 部分用例在参数校验阶段就返回（还没碰 Redis），统一放宽存根严格性，
        // 避免"未使用存根"噪声掩盖真正的失败
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        lenient().when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));

        service = new FunctionBatchImportService(
                permissionService,
                questionParseService,
                generationService,
                rangeMerger,
                aiDescriptionFeignClient,
                redisTemplate,
                synchronousExecutor,
                "internal-token"
        );
        UserContext.setUserId(ADMIN_ID);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void rejectsEmptyItems() {
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of());

        BizException exception = assertThrows(BizException.class, () -> service.start(request));
        assertEquals(QuestionErrorCode.BATCH_IMPORT_ITEMS_REQUIRED.getCode(), exception.getCode());
    }

    @Test
    void rejectsTooManyItems() {
        List<FunctionBatchImportItem> items = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            items.add(item("题目" + index));
        }
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(items);

        BizException exception = assertThrows(BizException.class, () -> service.start(request));
        assertEquals(QuestionErrorCode.BATCH_IMPORT_LIMIT_EXCEEDED.getCode(), exception.getCode());
    }

    @Test
    void rejectsIncompleteItemWithItsIndex() {
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(item("正常题目"), itemWithoutSolution()));

        BizException exception = assertThrows(BizException.class, () -> service.start(request));
        assertEquals(QuestionErrorCode.BATCH_IMPORT_ITEM_INCOMPLETE.getCode(), exception.getCode());
        // 文案里必须指出是第几条，批量导入时才知道去哪修
        assertTrue(exception.getMessage().contains("2"), exception.getMessage());
        verify(questionParseService, never()).insert(any(FunctionDto.class));
    }

    @Test
    void rejectsNonAdminCaller() {
        doAnswer(invocation -> {
            throw new BizException(org.example.serviceapi.exception.CommonErrorCode.NO_PERMISSION);
        }).when(permissionService).requireAdmin(anyLong());

        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(item("题目")));

        assertThrows(BizException.class, () -> service.start(request));
    }

    @Test
    void importsItemWithPreparedDescriptionWithoutCallingAi() {
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(100L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-1"));

        FunctionBatchImportItem prepared = item("两数之和");
        prepared.setDescription("给定一个整数数组，返回两个数的下标。");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        FunctionBatchImportTaskVo task = service.start(request);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(1, task.getSubmitted());
        assertEquals(0, task.getFailed());
        FunctionBatchImportItemVo state = task.getItems().getFirst();
        assertEquals("GENERATING", state.getStatus());
        assertEquals(100L, state.getQuestionId());
        assertEquals("task-1", state.getGenerationTaskId());
        assertEquals(Boolean.FALSE, state.getDescriptionGenerated());
        verify(aiDescriptionFeignClient, never()).generateFunctionDescription(anyString(), any());

        ArgumentCaptor<FunctionDto> dtoCaptor = ArgumentCaptor.forClass(FunctionDto.class);
        verify(questionParseService).insert(dtoCaptor.capture(), eq(3));
        assertEquals("给定一个整数数组，返回两个数的下标。", dtoCaptor.getValue().getDescription());
        assertEquals("原创改编", dtoCaptor.getValue().getSource());
        assertEquals("Solution", dtoCaptor.getValue().getClassName());
        assertTrue(dtoCaptor.getValue().getSamples().isEmpty());

        ArgumentCaptor<FunctionTestCaseGenerateRequest> generateCaptor =
                ArgumentCaptor.forClass(FunctionTestCaseGenerateRequest.class);
        verify(generationService).start(generateCaptor.capture());
        assertEquals(100L, generateCaptor.getValue().getQuestionId());
        assertEquals(20, generateCaptor.getValue().getCount());
        assertEquals("java", generateCaptor.getValue().getLanguage());
    }

    @Test
    void rewritesDescriptionThroughAiWhenMissing() {
        FunctionDescriptionResponse generated = new FunctionDescriptionResponse();
        generated.setDescription("重写后的题面");
        generated.setInputDesc("输入是一个整数");
        generated.setOutputDesc("输出是它的两倍");
        generated.setDifficulty(2);
        generated.setTags("数学,模拟");
        when(aiDescriptionFeignClient.generateFunctionDescription(anyString(), any()))
                .thenReturn(Result.success(generated));
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(200L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-2"));

        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(item("翻倍")));

        FunctionBatchImportTaskVo task = service.start(request);

        assertEquals("SUCCESS", task.getStatus());
        assertEquals(Boolean.TRUE, task.getItems().getFirst().getDescriptionGenerated());
        ArgumentCaptor<FunctionDto> dtoCaptor = ArgumentCaptor.forClass(FunctionDto.class);
        verify(questionParseService).insert(dtoCaptor.capture(), eq(3));
        assertEquals("重写后的题面", dtoCaptor.getValue().getDescription());
        assertEquals("输入是一个整数", dtoCaptor.getValue().getInputDesc());
        assertEquals("输出是它的两倍", dtoCaptor.getValue().getOutputDesc());
        assertEquals(2, dtoCaptor.getValue().getDifficulty());
        assertEquals("数学,模拟", dtoCaptor.getValue().getTags());
    }

    @Test
    void keepsCallerDifficultyAndTagsWhenProvided() {
        FunctionDescriptionResponse generated = new FunctionDescriptionResponse();
        generated.setDescription("重写后的题面");
        generated.setDifficulty(3);
        generated.setTags("模型给的标签");
        when(aiDescriptionFeignClient.generateFunctionDescription(anyString(), any()))
                .thenReturn(Result.success(generated));
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(201L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-3"));

        FunctionBatchImportItem prepared = item("翻倍");
        prepared.setDifficulty(1);
        prepared.setTags("手写标签");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        service.start(request);

        ArgumentCaptor<FunctionDto> dtoCaptor = ArgumentCaptor.forClass(FunctionDto.class);
        verify(questionParseService).insert(dtoCaptor.capture(), eq(3));
        assertEquals(1, dtoCaptor.getValue().getDifficulty());
        assertEquals("手写标签", dtoCaptor.getValue().getTags());
    }

    @Test
    void isolatesFailureSoOtherItemsStillImport() {
        when(aiDescriptionFeignClient.generateFunctionDescription(anyString(), any()))
                .thenThrow(new RuntimeException("AI 挂了"));
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(300L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-4"));

        FunctionBatchImportItem broken = item("坏题");
        FunctionBatchImportItem prepared = item("好题");
        prepared.setDescription("已备好的题面");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(broken, prepared));

        FunctionBatchImportTaskVo task = service.start(request);

        assertEquals("PARTIAL", task.getStatus());
        assertEquals(1, task.getSubmitted());
        assertEquals(1, task.getFailed());
        assertEquals("FAILED", task.getItems().get(0).getStatus());
        assertEquals(QuestionErrorCode.DESCRIPTION_GENERATE_FAILED.getMessage(),
                task.getItems().get(0).getErrorMessage());
        assertEquals("GENERATING", task.getItems().get(1).getStatus());
        assertEquals(300L, task.getItems().get(1).getQuestionId());
        verify(questionParseService, times(1)).insert(any(FunctionDto.class), eq(3));
    }

    @Test
    void marksAllItemsFailedWhenNothingSucceeds() {
        when(questionParseService.insert(any(FunctionDto.class), eq(3)))
                .thenThrow(new BizException(QuestionErrorCode.QUESTION_DUPLICATED));

        FunctionBatchImportItem prepared = item("重复题");
        prepared.setDescription("题面");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        FunctionBatchImportTaskVo task = service.start(request);

        assertEquals("FAILED", task.getStatus());
        assertEquals(0, task.getSubmitted());
        assertEquals(1, task.getFailed());
        assertEquals(QuestionErrorCode.QUESTION_DUPLICATED.getMessage(),
                task.getItems().getFirst().getErrorMessage());
    }

    @Test
    void rejectsItemWithoutDescriptionWhenAiGenerationDisabled() {
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setAllowDescriptionGeneration(false);
        request.setItems(List.of(item("没题面")));

        FunctionBatchImportTaskVo task = service.start(request);

        assertEquals("FAILED", task.getStatus());
        assertEquals(QuestionErrorCode.QUESTION_CONTENT_REQUIRED.getMessage(),
                task.getItems().getFirst().getErrorMessage());
        verify(aiDescriptionFeignClient, never()).generateFunctionDescription(anyString(), any());
    }

    @Test
    void runsWorkerWithImportedUserIdAndClearsItAfterwards() {
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenAnswer(invocation -> {
            // 后台线程里必须能读到发起人的身份，否则建题会写出空的 create_user_id
            assertEquals(ADMIN_ID, UserContext.getUserId());
            return 400L;
        });
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-5"));

        FunctionBatchImportItem prepared = item("身份题");
        prepared.setDescription("题面");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        service.start(request);

        assertNull(UserContext.getUserId());
    }

    @Test
    void derivesDistinctSeedsPerItemAndHonoursItemOverrides() {
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(500L, 501L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-a"), generationTask("task-b"));

        FunctionBatchImportItem first = item("题一");
        first.setDescription("题面一");
        first.setSeed(42L);
        first.setCaseCount(5);
        FunctionBatchImportItem second = item("题二");
        second.setDescription("题面二");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(first, second));

        service.start(request);

        ArgumentCaptor<FunctionTestCaseGenerateRequest> captor =
                ArgumentCaptor.forClass(FunctionTestCaseGenerateRequest.class);
        verify(generationService, times(2)).start(captor.capture());
        List<FunctionTestCaseGenerateRequest> requests = captor.getAllValues();
        assertEquals(42L, requests.get(0).getSeed());
        assertEquals(5, requests.get(0).getCount());
        assertNotNull(requests.get(1).getSeed());
        assertTrue(!requests.get(1).getSeed().equals(requests.get(0).getSeed()));
    }

    @Test
    void passesParameterRangesIntoGenerationRequest() {
        FunctionDescriptionResponse generated = new FunctionDescriptionResponse();
        generated.setDescription("题面");
        org.example.serviceapi.dto.ai.FunctionParameterRange range =
                new org.example.serviceapi.dto.ai.FunctionParameterRange();
        range.setName("depth");
        range.setMin(1L);
        range.setMax(100L);
        generated.setParameterRanges(List.of(range));
        when(aiDescriptionFeignClient.generateFunctionDescription(anyString(), any()))
                .thenReturn(Result.success(generated));
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(700L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-7"));

        FunctionBatchImportItem prepared = item("树题");
        prepared.setParameterConfig("[{\"type\":\"int\",\"name\":\"depth\"}]");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        service.start(request);

        ArgumentCaptor<FunctionTestCaseGenerateRequest> captor =
                ArgumentCaptor.forClass(FunctionTestCaseGenerateRequest.class);
        verify(generationService).start(captor.capture());
        String override = captor.getValue().getParameterConfigOverride();
        assertNotNull(override);
        assertTrue(override.contains("\"min\":1"), override);
        assertTrue(override.contains("\"max\":100"), override);
        // 覆盖只用于生成：落库配置保持原样，不带取值域键
        ArgumentCaptor<FunctionDto> dtoCaptor = ArgumentCaptor.forClass(FunctionDto.class);
        verify(questionParseService).insert(dtoCaptor.capture(), eq(3));
        assertEquals("[{\"type\":\"int\",\"name\":\"depth\"}]", dtoCaptor.getValue().getParameterConfig());
    }

    @Test
    void itemProvidedRangesWinOverAiSuggestions() {
        FunctionDescriptionResponse generated = new FunctionDescriptionResponse();
        generated.setDescription("题面");
        org.example.serviceapi.dto.ai.FunctionParameterRange aiRange =
                new org.example.serviceapi.dto.ai.FunctionParameterRange();
        aiRange.setName("depth");
        aiRange.setMin(5L);
        generated.setParameterRanges(List.of(aiRange));
        when(aiDescriptionFeignClient.generateFunctionDescription(anyString(), any()))
                .thenReturn(Result.success(generated));
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(701L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-8"));

        org.example.serviceapi.dto.ai.FunctionParameterRange manual =
                new org.example.serviceapi.dto.ai.FunctionParameterRange();
        manual.setName("depth");
        manual.setMin(1L);
        FunctionBatchImportItem prepared = item("树题");
        prepared.setParameterConfig("[{\"type\":\"int\",\"name\":\"depth\"}]");
        prepared.setParameterRanges(List.of(manual));
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        service.start(request);

        ArgumentCaptor<FunctionTestCaseGenerateRequest> captor =
                ArgumentCaptor.forClass(FunctionTestCaseGenerateRequest.class);
        verify(generationService).start(captor.capture());
        assertTrue(captor.getValue().getParameterConfigOverride().contains("\"min\":1"),
                captor.getValue().getParameterConfigOverride());
    }

    @Test
    void storesAndReadsTaskSnapshotThroughRedis() {
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenReturn(600L);
        when(generationService.start(any(FunctionTestCaseGenerateRequest.class)))
                .thenReturn(generationTask("task-6"));

        FunctionBatchImportItem prepared = item("可查题");
        prepared.setDescription("题面");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        FunctionBatchImportTaskVo started = service.start(request);
        assertNotNull(redisStore.get(RedisContext.FUNCTION_BATCH_IMPORT_TASK_KEY + started.getTaskId()));

        FunctionBatchImportTaskVo loaded = service.getTask(started.getTaskId());
        assertEquals("SUCCESS", loaded.getStatus());
        assertEquals(600L, loaded.getItems().getFirst().getQuestionId());
    }

    @Test
    void rejectsUnknownTaskId() {
        BizException exception = assertThrows(BizException.class, () -> service.getTask("missing-task"));
        assertEquals(QuestionErrorCode.GENERATION_TASK_NOT_FOUND.getCode(), exception.getCode());
    }

    @Test
    void exposesUserContextClearedEvenWhenAllItemsFail() {
        AtomicReference<Long> seen = new AtomicReference<>();
        when(questionParseService.insert(any(FunctionDto.class), eq(3))).thenAnswer(invocation -> {
            seen.set(UserContext.getUserId());
            throw new BizException(QuestionErrorCode.QUESTION_SAVE_FAILED);
        });

        FunctionBatchImportItem prepared = item("失败题");
        prepared.setDescription("题面");
        FunctionBatchImportRequest request = new FunctionBatchImportRequest();
        request.setItems(List.of(prepared));

        service.start(request);

        assertEquals(ADMIN_ID, seen.get());
        assertNull(UserContext.getUserId());
    }

    private FunctionBatchImportItem item(String title) {
        FunctionBatchImportItem item = new FunctionBatchImportItem();
        item.setTitle(title);
        item.setMethodName("solve");
        item.setParameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]");
        item.setOutputType("int");
        item.setSolutionCode("class Solution { public int solve(int value) { return value; } }");
        return item;
    }

    private FunctionBatchImportItem itemWithoutSolution() {
        FunctionBatchImportItem item = item("缺解法");
        item.setSolutionCode(" ");
        return item;
    }

    private FunctionTestCaseGenerationTaskVo generationTask(String taskId) {
        return FunctionTestCaseGenerationTaskVo.builder()
                .taskId(taskId)
                .status("PENDING")
                .build();
    }
}

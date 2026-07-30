package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.dto.FunctionTestCaseGenerateRequest;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.vo.FunctionTestCaseGenerationTaskVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionTestCaseGenerationServiceTest {

    @Mock
    private QuestionPermissionService permissionService;
    @Mock
    private FunctionConfigMapper functionConfigMapper;
    @Mock
    private FunctionRandomInputGenerator inputGenerator;
    @Mock
    private FunctionQuestionParseService functionQuestionParseService;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private final FunctionCaseDataConverter caseDataConverter =
            new FunctionCaseDataConverter(new ObjectMapper());

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void startsRandomGenerationWithoutAppendingQuestionSample() {
        UserContext.setUserId(10L);
        Question question = Question.builder()
                .questionId(1L)
                .questionType(QuestionType.FUNCTION)
                .createUserId(10L)
                .build();
        FunctionConfig config = FunctionConfig.builder()
                .questionId(1L)
                .parameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]")
                .outputType("int")
                .build();
        when(permissionService.requireOwnerOrAdmin(1L, 10L)).thenReturn(question);
        when(functionConfigMapper.selectOne(any())).thenReturn(config);
        when(inputGenerator.generate(config.getParameterConfig(), 2, 7L)).thenReturn(List.of("1", "2"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        FunctionTestCaseGenerateRequest request = new FunctionTestCaseGenerateRequest();
        request.setQuestionId(1L);
        request.setLanguage("java");
        request.setStandardAnswer("class Solution { int solve(int value) { return value; } }");
        request.setCount(2);
        request.setSeed(7L);

        FunctionTestCaseGenerationTaskVo task = service().start(request);

        ArgumentCaptor<DebugDto> debugCaptor = ArgumentCaptor.forClass(DebugDto.class);
        verify(hashOperations).put(anyString(), anyString(), debugCaptor.capture());
        assertEquals("PENDING", task.getStatus());
        assertEquals(2, debugCaptor.getValue().getTests().size());
        assertFalse(debugCaptor.getValue().getIncludeQuestionSample());
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    void persistsOutputsProducedByStandardAnswer() {
        FunctionTestCaseGenerationTaskVo task = FunctionTestCaseGenerationTaskVo.builder()
                .taskId("task-1")
                .questionId(1L)
                .userId(10L)
                .status("PENDING")
                .requestedCount(2)
                .generatedCount(0)
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisContext.FUNCTION_CASE_GENERATION_TASK_KEY + "task-1"))
                .thenReturn(task);
        when(functionQuestionParseService.insertTestCases(any(), anyList())).thenReturn(2);

        JudgeReturnRecordDto first = result("1", "2");
        JudgeReturnRecordDto second = result("3", "4");
        service().complete("task-1", List.of(first, second));

        ArgumentCaptor<List<FunctionTestCaseDto>> casesCaptor = ArgumentCaptor.forClass(List.class);
        verify(functionQuestionParseService).insertTestCases(any(), casesCaptor.capture());
        assertEquals("2", casesCaptor.getValue().get(0).getOutput());
        assertEquals("SUCCESS", task.getStatus());
        assertEquals(2, task.getGeneratedCount());
    }

    private JudgeReturnRecordDto result(String input, String output) {
        JudgeReturnRecordDto result = new JudgeReturnRecordDto();
        result.setSubmitStatus("WA");
        result.setInputData(input);
        result.setUserOutput(output);
        return result;
    }

    private FunctionTestCaseGenerationService service() {
        return new FunctionTestCaseGenerationService(
                permissionService,
                functionConfigMapper,
                inputGenerator,
                caseDataConverter,
                functionQuestionParseService,
                redisTemplate,
                rabbitTemplate
        );
    }
}

package org.example.servicejudge.Mq.handler;

import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.GetDebugTestDto;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.entry.FunctionConfig;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.judge.JudgeService;
import org.example.servicejudge.mapper.FunctionConfigMapper;
import org.example.servicejudge.mapper.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调试判题处理器单元测试（适配构造器注入后的新结构，断言与原版等价）。
 */
@ExtendWith(MockitoExtension.class)
class JudgeDebugHandlerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private FunctionConfigMapper functionConfigMapper;

    @Mock
    private JudgeService judgeService;

    private JudgeDebugHandler judgeDebugHandler;

    @BeforeEach
    void setUp() {
        judgeDebugHandler = new JudgeDebugHandler(
                rabbitTemplate,
                redisTemplate,
                questionMapper,
                judgeService,
                functionConfigMapper);
    }

    @Test
    void functionDebugCompilesOnceAndRunsAllCases() throws IOException {
        FunctionConfig config = FunctionConfig.builder()
                .questionId(1L)
                .methodName("doubleValue")
                .parameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]")
                .outputType("int")
                .build();
        when(functionConfigMapper.selectOne(any())).thenReturn(config);
        when(judgeService.batchDebugCode(contains("class Solution {}"), contains("doubleValue"), eq("java"), anyList()))
                .thenReturn(List.of(JudgeRecord.builder().submitStatus("AC").build()));

        DebugDto debugDto = new DebugDto();
        debugDto.setQuestionId(1L);
        debugDto.setUserId(2L);
        debugDto.setLanguage("java");
        debugDto.setCode("class Solution {}");

        GetDebugTestDto first = new GetDebugTestDto();
        first.setInput("1");
        first.setOutput("2");
        GetDebugTestDto second = new GetDebugTestDto();
        second.setInput("3");
        second.setOutput("6");

        List<JudgeReturnRecordDto> results = judgeDebugHandler.debugFunction(
                debugDto,
                List.of(first, second)
        );

        ArgumentCaptor<List<TestDto>> testCasesCaptor = ArgumentCaptor.forClass(List.class);
        verify(judgeService).batchDebugCode(
                contains("class Solution {}"),
                contains("doubleValue"),
                eq("java"),
                testCasesCaptor.capture()
        );
        assertEquals(2, testCasesCaptor.getValue().size());
        assertEquals("1", testCasesCaptor.getValue().get(0).getInputData());
        assertEquals("6", testCasesCaptor.getValue().get(1).getExpectedOutput());
        assertEquals(1, results.size());
        assertEquals(2L, results.get(0).getUserId());
        assertEquals("AC", results.get(0).getSubmitStatus());
    }
}

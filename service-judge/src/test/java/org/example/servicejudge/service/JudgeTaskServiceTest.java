package org.example.servicejudge.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicejudge.entry.*;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 判题任务服务单元测试：领取 CAS 短路、ACM/FUNCTION 分派、
 * judge_record 插入与 OutboxPro 事件参数、WA/RE/TLE 才发 AI 建议事件。
 */
@ExtendWith(MockitoExtension.class)
class JudgeTaskServiceTest {

    @Mock
    private JudgeInterface judge;
    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private TestCaseMapper testCaseMapper;
    @Mock
    private JudgeRecordMapper judgeRecordMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private FunctionConfigMapper functionConfigMapper;
    @Mock
    private FunctionTestCaseMapper functionTestCaseMapper;
    @Mock
    private OutboxProPublisher outboxPublisher;

    private JudgeTaskService judgeTaskService;

    @BeforeEach
    void setUp() {
        judgeTaskService = new JudgeTaskService(
                judge,
                submitRecordMapper,
                testCaseMapper,
                judgeRecordMapper,
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                outboxPublisher);
        // 单测中自注入指向自身：事务语义不生效，但调用链与参数可验证
        ReflectionTestUtils.setField(judgeTaskService, "self", judgeTaskService);
    }

    @Test
    void handleSubmitShortCircuitsWhenClaimFails() throws IOException {
        SubmitRecord record = pendingRecord();
        when(submitRecordMapper.selectById(10L)).thenReturn(record);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(0);

        assertNull(judgeTaskService.handleSubmit(10L));

        verifyNoInteractions(judge, judgeRecordMapper, outboxPublisher);
    }

    @Test
    void handleSubmitShortCircuitsWhenRecordMissing() throws IOException {
        when(submitRecordMapper.selectById(10L)).thenReturn(null);

        assertNull(judgeTaskService.handleSubmit(10L));

        verify(submitRecordMapper, never()).updateRecordToJudge(10L);
        verifyNoInteractions(judge, judgeRecordMapper, outboxPublisher);
    }

    @Test
    void acmSubmitInsertsRecordAndPublishesCallbackOnly() throws IOException {
        SubmitRecord record = claimedRecord();
        Question question = acmQuestion();
        when(submitRecordMapper.selectById(10L)).thenReturn(record);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(1);
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(testCaseMapper.selectList(any())).thenReturn(List.of(new TestCase()));
        JudgeRecord acResult = resultOf("AC", null);
        when(judge.batchExecuteCode(eq("print(1)"), eq("python"), anyList())).thenReturn(acResult);
        stubInsertBackfillingId(20L);

        JudgeRecord finalResult = judgeTaskService.handleSubmit(10L);

        assertEquals(acResult, finalResult);
        verify(judgeRecordMapper).insert(acResult);
        assertEquals(10L, acResult.getSubmitRecordId());
        assertEquals(1, acResult.getTestTotal());
        // AC 结果只发结果回调，不发 AI 建议
        verify(outboxPublisher, times(1)).publish(
                EventTypes.JUDGE_RESULT_CALLBACK,
                20L);
        verifyNoInteractions(functionConfigMapper, functionTestCaseMapper);
    }

    @Test
    void functionSubmitBuildsMainAndInsertsRecord() throws IOException {
        SubmitRecord record = claimedRecord();
        record.setLanguage("Java");
        Question question = new Question();
        question.setQuestionType(QuestionType.FUNCTION);
        when(submitRecordMapper.selectById(10L)).thenReturn(record);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(1);
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(functionTestCaseMapper.selectList(any())).thenReturn(List.of(new FunctionTestCase()));
        FunctionConfig config = FunctionConfig.builder()
                .questionId(1L)
                .methodName("doubleValue")
                .parameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]")
                .outputType("int")
                .build();
        when(functionConfigMapper.selectOne(any())).thenReturn(config);
        JudgeRecord acResult = resultOf("AC", null);
        when(judge.batchExecuteCode(anyString(), anyString(), eq("Java"), anyList())).thenReturn(acResult);
        stubInsertBackfillingId(21L);

        JudgeRecord finalResult = judgeTaskService.handleSubmit(10L);

        assertEquals(acResult, finalResult);
        verify(judgeRecordMapper).insert(acResult);
        verify(outboxPublisher, times(1)).publish(
                EventTypes.JUDGE_RESULT_CALLBACK,
                21L);
    }

    @Test
    void waResultAlsoPublishesAiAdviceEvent() throws IOException {
        SubmitRecord record = claimedRecord();
        when(submitRecordMapper.selectById(10L)).thenReturn(record);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(1);
        when(questionMapper.selectById(1L)).thenReturn(acmQuestion());
        when(testCaseMapper.selectList(any())).thenReturn(List.of(new TestCase()));
        JudgeRecord waResult = resultOf("WA", 30L);
        when(judge.batchExecuteCode(eq("print(1)"), eq("python"), anyList())).thenReturn(waResult);
        stubInsertBackfillingId(30L);

        judgeTaskService.handleSubmit(10L);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher, times(2)).publish(
                anyString(),
                payloadCaptor.capture());
        verify(outboxPublisher).publish(
                EventTypes.JUDGE_RESULT_CALLBACK,
                30L);

        Object aiPayload = payloadCaptor.getAllValues().get(1);
        org.example.serviceapi.dto.ai.AiAdviceWADto aiAdvice =
                (org.example.serviceapi.dto.ai.AiAdviceWADto) aiPayload;
        assertEquals(5L, aiAdvice.getUserId());
        assertEquals(10L, aiAdvice.getSubmitId());
        assertEquals(1L, aiAdvice.getQuestionId());
        assertEquals(30L, aiAdvice.getJudgeRecordId());
        assertEquals("python", aiAdvice.getLanguage());
        assertEquals("WA", aiAdvice.getJudgeStatus());
        assertEquals("ai_advice1:5:30", aiAdvice.getMessageId());
        verify(outboxPublisher).publish(
                EventTypes.AI_ADVICE_REQUEST,
                aiAdvice);
    }

    @Test
    void retryPathSkipsAiAdviceEvenOnWa() throws IOException {
        SubmitRecord record = claimedRecord();
        when(questionMapper.selectById(1L)).thenReturn(acmQuestion());
        when(testCaseMapper.selectList(any())).thenReturn(List.of(new TestCase()));
        JudgeRecord waResult = resultOf("WA", 40L);
        when(judge.batchExecuteCode(eq("print(1)"), eq("python"), anyList())).thenReturn(waResult);
        stubInsertBackfillingId(40L);

        judgeTaskService.judgeAndPersist(record, false);

        // Retry 流程只发结果回调，不发 AI 建议（sendAiAdvice=false）
        verify(outboxPublisher, times(1)).publish(
                EventTypes.JUDGE_RESULT_CALLBACK,
                40L);
        verify(outboxPublisher, never()).publish(
                eq(EventTypes.AI_ADVICE_REQUEST),
                any());
    }

    @Test
    void unsupportedQuestionTypeFailsFast() {
        SubmitRecord record = claimedRecord();
        Question question = new Question();
        question.setQuestionType(null);
        when(questionMapper.selectById(1L)).thenReturn(question);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> judgeTaskService.judgeAndPersist(record, true));

        verifyNoInteractions(judge, outboxPublisher);
    }

    private SubmitRecord pendingRecord() {
        SubmitRecord record = new SubmitRecord();
        record.setSubmitRecordId(10L);
        record.setQuestionId(1L);
        record.setUserId(5L);
        record.setSubmitContent("print(1)");
        record.setLanguage("python");
        record.setJudgeStatus("pending");
        return record;
    }

    private SubmitRecord claimedRecord() {
        SubmitRecord record = pendingRecord();
        record.setJudgeStatus("judging");
        return record;
    }

    private Question acmQuestion() {
        Question question = new Question();
        question.setQuestionId(1L);
        question.setQuestionType(QuestionType.ACM);
        return question;
    }

    private JudgeRecord resultOf(String status, Long judgeRecordId) {
        JudgeRecord record = new JudgeRecord();
        record.setSubmitStatus(status);
        record.setJudgeRecordId(judgeRecordId);
        return record;
    }

    /** 模拟 MyBatis-Plus 插入时回填自增主键。 */
    private void stubInsertBackfillingId(long id) {
        doAnswer(invocation -> {
            JudgeRecord inserted = invocation.getArgument(0);
            inserted.setJudgeRecordId(id);
            return 1;
        }).when(judgeRecordMapper).insert(any(JudgeRecord.class));
    }
}

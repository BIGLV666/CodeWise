package org.example.servicejudge.Mq.handler;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.JudgeRecordMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.example.servicejudge.service.JudgeTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 失败重试处理器单元测试（适配去 Channel 化后的新结构）：
 * 幂等抢占、复用已有结果、判题复用 JudgeTaskService（不发 AI 事件）、
 * 失败登记 lastError 并上抛。
 */
@ExtendWith(MockitoExtension.class)
class JudgeRetryHandlerTest {

    @Mock
    private FailureSubmitMapper failureSubmitMapper;
    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private JudgeRecordMapper judgeRecordMapper;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private JudgeTaskService judgeTaskService;

    private JudgeRetryHandler judgeRetryHandler;

    @BeforeEach
    void setUp() {
        judgeRetryHandler = new JudgeRetryHandler(
                failureSubmitMapper,
                submitRecordMapper,
                judgeRecordMapper,
                eventPublisher,
                judgeTaskService);
    }

    @Test
    void preemptedRecordReturnsNormally() throws IOException {
        when(failureSubmitMapper.updateStatusToRetry(1L)).thenReturn(0);

        judgeRetryHandler.handle(1L);

        verify(failureSubmitMapper, never()).selectById(1L);
        verifyNoInteractions(judgeTaskService, eventPublisher);
    }

    @Test
    void reusesExistingJudgeResultWithoutExecutingJudgeOrSendingAi() throws IOException {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setFailureSubmitId(1L);
        failureSubmit.setSubmitRecordId(10L);

        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitRecordId(10L);
        submitRecord.setJudgeStatus("judging");

        JudgeRecord existingResult = new JudgeRecord();
        existingResult.setJudgeRecordId(20L);
        existingResult.setSubmitRecordId(10L);

        when(failureSubmitMapper.updateStatusToRetry(1L)).thenReturn(1);
        when(failureSubmitMapper.selectById(1L)).thenReturn(failureSubmit);
        when(submitRecordMapper.selectById(10L)).thenReturn(submitRecord);
        when(judgeRecordMapper.selectOne(any())).thenReturn(existingResult);
        when(failureSubmitMapper.updateStatus(1L, FailureSubmitStatus.SUCCESS.getValue())).thenReturn(1);

        judgeRetryHandler.handle(1L);

        verify(eventPublisher).publish(
                MqContexts.Question_EXCHANGE,
                MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY,
                EventTypes.JUDGE_RESULT_CALLBACK,
                20L);
        verify(failureSubmitMapper, never()).updateFailureStatus(any(), any(), any());
        verifyNoInteractions(judgeTaskService);
    }

    @Test
    void executesThroughJudgeTaskServiceWithoutAiAdvice() throws IOException {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setFailureSubmitId(1L);
        failureSubmit.setSubmitRecordId(10L);

        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitRecordId(10L);
        submitRecord.setJudgeStatus("failure");

        when(failureSubmitMapper.updateStatusToRetry(1L)).thenReturn(1);
        when(failureSubmitMapper.selectById(1L)).thenReturn(failureSubmit);
        when(submitRecordMapper.selectById(10L)).thenReturn(submitRecord);
        when(judgeRecordMapper.selectOne(any())).thenReturn(null);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(1);
        when(failureSubmitMapper.updateStatus(1L, FailureSubmitStatus.SUCCESS.getValue())).thenReturn(1);

        judgeRetryHandler.handle(1L);

        // 判题 + 落库 + 结果回调事件复用 JudgeTaskService，且不发 AI 建议
        verify(judgeTaskService).judgeAndPersist(submitRecord, false);
        verify(failureSubmitMapper).updateStatus(1L, FailureSubmitStatus.SUCCESS.getValue());
        verify(failureSubmitMapper, never()).updateFailureStatus(any(), any(), any());
    }

    @Test
    void storesLastErrorAndThrowsWhenRetryFails() {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setFailureSubmitId(1L);
        failureSubmit.setSubmitRecordId(10L);

        when(failureSubmitMapper.updateStatusToRetry(1L)).thenReturn(1);
        when(failureSubmitMapper.selectById(1L)).thenReturn(failureSubmit);
        when(submitRecordMapper.selectById(10L)).thenReturn(null);
        when(failureSubmitMapper.updateFailureStatus(
                eq(1L),
                eq(FailureSubmitStatus.FAILURE.getValue()),
                any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> judgeRetryHandler.handle(1L));

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(failureSubmitMapper).updateFailureStatus(
                eq(1L),
                eq(FailureSubmitStatus.FAILURE.getValue()),
                errorCaptor.capture());
        assertTrue(errorCaptor.getValue().contains("提交记录不存在"));
        assertTrue(errorCaptor.getValue().length() <= 4000);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void judgeFailureMarksFailureAndRethrows() throws IOException {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setFailureSubmitId(1L);
        failureSubmit.setSubmitRecordId(10L);

        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitRecordId(10L);
        submitRecord.setJudgeStatus("failure");

        when(failureSubmitMapper.updateStatusToRetry(1L)).thenReturn(1);
        when(failureSubmitMapper.selectById(1L)).thenReturn(failureSubmit);
        when(submitRecordMapper.selectById(10L)).thenReturn(submitRecord);
        when(judgeRecordMapper.selectOne(any())).thenReturn(null);
        when(submitRecordMapper.updateRecordToJudge(10L)).thenReturn(1);
        doThrow(new java.io.IOException("docker down"))
                .when(judgeTaskService).judgeAndPersist(any(SubmitRecord.class), anyBoolean());
        when(failureSubmitMapper.updateFailureStatus(
                eq(1L),
                eq(FailureSubmitStatus.FAILURE.getValue()),
                any())).thenReturn(1);

        assertThrows(java.io.IOException.class, () -> judgeRetryHandler.handle(1L));

        verify(failureSubmitMapper).updateFailureStatus(
                eq(1L),
                eq(FailureSubmitStatus.FAILURE.getValue()),
                any());
    }
}

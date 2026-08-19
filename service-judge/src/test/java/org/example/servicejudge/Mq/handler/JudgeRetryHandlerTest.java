package org.example.servicejudge.Mq.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.FunctionConfigMapper;
import org.example.servicejudge.mapper.FunctionTestCaseMapper;
import org.example.servicejudge.mapper.JudgeRecordMapper;
import org.example.servicejudge.mapper.QuestionMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.example.servicejudge.mapper.TestCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgeRetryHandlerTest {

    @Mock
    private JudgeInterface judge;
    @Mock
    private FailureSubmitMapper failureSubmitMapper;
    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private JudgeRecordMapper judgeRecordMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private FunctionConfigMapper functionConfigMapper;
    @Mock
    private FunctionTestCaseMapper functionTestCaseMapper;
    @Mock
    private TestCaseMapper testCaseMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;

    private JudgeRetryHandler judgeRetryHandler;
    private Message message;

    @BeforeEach
    void setUp() {
        judgeRetryHandler = new JudgeRetryHandler();
        ReflectionTestUtils.setField(judgeRetryHandler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(judgeRetryHandler, "judge", judge);
        ReflectionTestUtils.setField(judgeRetryHandler, "failureSubmitMapper", failureSubmitMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "submitRecordMapper", submitRecordMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "judgeRecordMapper", judgeRecordMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "functionConfigMapper", functionConfigMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "functionTestCaseMapper", functionTestCaseMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "testCaseMapper", testCaseMapper);
        ReflectionTestUtils.setField(judgeRetryHandler, "rabbitTemplate", rabbitTemplate);

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(99L);
        message = new Message("1".getBytes(), properties);
    }

    @Test
    void reusesExistingJudgeResultWithoutExecutingJudgeOrSendingAi() throws Exception {
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

        judgeRetryHandler.handle("1", channel, message);

        verify(rabbitTemplate).convertAndSend(
                MqContexts.Question_EXCHANGE,
                MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY,
                20L);
        verify(channel).basicAck(99L, false);
        verify(failureSubmitMapper, never()).updateFailureStatus(any(), any(), any());
        verifyNoInteractions(judge);
    }

    @Test
    void storesLastErrorAndNacksWhenRetryFails() throws Exception {
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

        judgeRetryHandler.handle("1", channel, message);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(failureSubmitMapper).updateFailureStatus(
                eq(1L),
                eq(FailureSubmitStatus.FAILURE.getValue()),
                errorCaptor.capture());
        assertTrue(errorCaptor.getValue().contains("提交记录不存在"));
        assertTrue(errorCaptor.getValue().length() <= 4000);
        verify(channel).basicNack(99L, false, false);
        verifyNoInteractions(rabbitTemplate);
    }
}


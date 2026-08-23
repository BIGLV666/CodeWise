package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.outbox.OutboxService;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SubmitRecordHandel 单测：消息信封/裸格式双读、CAS 幂等收尾、ACK 后置、
 * Outbox 转发、失败 Redis 计数重试、WebSocket 推送大字段截断。
 */
@ExtendWith(MockitoExtension.class)
class SubmitRecordHandelTest {

    private static final long JUDGE_RECORD_ID = 123L;
    private static final String TRUNCATED_SUFFIX = "...[truncated]";

    @Mock
    private SubmitRecordMapper submitRecordMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private JudgeRecordMapper judgeRecordMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OutboxService outboxService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private Channel channel;

    @InjectMocks
    private SubmitRecordHandel handler;

    @BeforeEach
    void setUp() {
        // 宽松桩：各用例按需触达，避免严格模式对未用桩误报
        lenient().when(judgeRecordMapper.selectById(JUDGE_RECORD_ID)).thenReturn(judgeRecord());
        lenient().when(submitRecordMapper.selectById(555L)).thenReturn(submitRecord());
        lenient().when(submitRecordMapper.updateJudgeSuccess(eq(555L), eq("WA"), eq(12), eq(2048))).thenReturn(1);
        lenient().when(questionMapper.updateTotal(9L)).thenReturn(1);
        lenient().when(questionMapper.updateTotalAc(9L)).thenReturn(1);
        // 事务模板直接执行回调：模拟"事务体运行、提交后返回"
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(1L);
    }

    @Test
    void readsJudgeRecordIdFromBareLongBody() throws Exception {
        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        verify(judgeRecordMapper).selectById(JUDGE_RECORD_ID);
        // 非 AC：只累计提交数
        verify(questionMapper).updateTotal(9L);
        verify(questionMapper, never()).updateTotalAc(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void readsJudgeRecordIdFromEnvelopeBody() throws Exception {
        String envelopeBody = EnvelopeCodec.serialize(
                EnvelopeCodec.wrap(EventTypes.JUDGE_RESULT_CALLBACK, "service-judge", null, JUDGE_RECORD_ID));

        handler.handle(envelopeBody, channel, amqpMessage());

        verify(judgeRecordMapper).selectById(JUDGE_RECORD_ID);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void discardsPoisonBodyWithoutTransaction() throws Exception {
        handler.handle("not-a-json", channel, amqpMessage());

        verify(channel).basicAck(1L, false);
        // 毒消息不进入事务（stubbing 不计 interaction，故用 verifyNoInteractions）
        verifyNoInteractions(transactionTemplate);
        verify(judgeRecordMapper, never()).selectById(any());
    }

    @Test
    void countsAcOnceAndAcksAfterTransactionCommit() throws Exception {
        JudgeRecord judgeRecord = judgeRecord();
        judgeRecord.setSubmitStatus("AC");
        when(judgeRecordMapper.selectById(JUDGE_RECORD_ID)).thenReturn(judgeRecord);
        when(submitRecordMapper.updateJudgeSuccess(eq(555L), eq("AC"), eq(12), eq(2048))).thenReturn(1);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        verify(submitRecordMapper).updateJudgeSuccess(555L, "AC", 12, 2048);
        verify(questionMapper).updateTotal(9L);
        verify(questionMapper).updateTotalAc(9L);
        verify(outboxService, never()).append(any(), any(), any(), any());

        // ACK 严格在事务提交之后
        InOrder inOrder = inOrder(transactionTemplate, channel);
        inOrder.verify(transactionTemplate).execute(any());
        inOrder.verify(channel).basicAck(1L, false);
    }

    @Test
    void skipsIdempotentlyWhenCasMissesOnAlreadySuccessRecord() throws Exception {
        when(submitRecordMapper.updateJudgeSuccess(eq(555L), eq("WA"), eq(12), eq(2048))).thenReturn(0);
        SubmitRecord finished = submitRecord();
        finished.setJudgeStatus("success");
        when(submitRecordMapper.selectById(555L)).thenReturn(finished);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        verify(questionMapper, never()).updateTotal(any());
        verify(questionMapper, never()).updateTotalAc(any());
        verify(outboxService, never()).append(any(), any(), any(), any());
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        // 幂等跳过不推送 WS
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void nacksWithRequeueOnFirstTransactionFailure() throws Exception {
        // CAS 未命中且状态仍 pending -> 事务体抛异常（execute 向上抛出模拟回滚）
        when(submitRecordMapper.updateJudgeSuccess(eq(555L), eq("WA"), eq(12), eq(2048))).thenReturn(0);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        verify(hashOperations).increment(eq("question:judge:retry-count"), anyString(), eq(1L));
        verify(hashOperations, never()).put(anyString(), any(), any());
        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(questionMapper, never()).updateTotal(any());
    }

    @Test
    void recordsFailureAndDiscardsWhenRetryExhausted() throws Exception {
        when(submitRecordMapper.updateJudgeSuccess(eq(555L), eq("WA"), eq(12), eq(2048))).thenReturn(0);
        when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(3L);

        String body = String.valueOf(JUDGE_RECORD_ID);
        handler.handle(body, channel, amqpMessage());

        verify(hashOperations).put(eq("question:judge:failed"), anyString(), eq(body));
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void forwardsReviewResultThroughOutbox() throws Exception {
        SubmitRecord review = submitRecord();
        review.setSubmitScene("REVIEW");
        when(submitRecordMapper.selectById(555L)).thenReturn(review);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(
                eq(EventTypes.REVIEW_JUDGE_RECORD),
                eq(MqContexts.REVIEW_EXCHANGE),
                eq(MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY),
                payloadCaptor.capture());
        ReviewJudgeRecordDto payload = (ReviewJudgeRecordDto) payloadCaptor.getValue();
        assertEquals(JUDGE_RECORD_ID, payload.getJudgeRecordId());
        assertEquals(555L, payload.getSubmitRecordId());
        assertEquals(9L, payload.getQuestionId());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void truncatesOversizedFieldsOnlyForWebsocketPush() throws Exception {
        String huge = "x".repeat(SubmitRecordHandel.MAX_FIELD_LENGTH * 2);

        JudgeRecord judgeRecord = judgeRecord();
        judgeRecord.setLog(huge);
        judgeRecord.setExpectedOutput(huge);
        judgeRecord.setUserOutput(huge);
        when(judgeRecordMapper.selectById(JUDGE_RECORD_ID)).thenReturn(judgeRecord);

        SubmitRecord submitRecord = submitRecordWithHugeCode(huge);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        // DB 收尾使用 CAS 定向更新（状态/耗时/内存取原始值，不回写大字段）
        verify(submitRecordMapper).updateJudgeSuccess(555L, "WA", 12, 2048);
        verify(submitRecordMapper, never()).updateById(any(SubmitRecord.class));

        // WS 推送 DTO 的四个大字段被截断至上限，且带截断标记
        JudgeResultDto pushed = captureWebsocketResult();
        assertTrue(pushed.getCode().length() <= SubmitRecordHandel.MAX_FIELD_LENGTH);
        assertTrue(pushed.getLog().length() <= SubmitRecordHandel.MAX_FIELD_LENGTH);
        assertTrue(pushed.getExpectedOutput().length() <= SubmitRecordHandel.MAX_FIELD_LENGTH);
        assertTrue(pushed.getActual().length() <= SubmitRecordHandel.MAX_FIELD_LENGTH);
        assertTrue(pushed.getCode().endsWith(TRUNCATED_SUFFIX));
        assertTrue(pushed.getLog().endsWith(TRUNCATED_SUFFIX));
        assertTrue(pushed.getExpectedOutput().endsWith(TRUNCATED_SUFFIX));
        assertTrue(pushed.getActual().endsWith(TRUNCATED_SUFFIX));
        // error 不截断：原始长度保留
        assertEquals(judgeRecord.getErrorMsg(), pushed.getError());
        // 非大字段不受影响
        assertEquals("WA", pushed.getSubmitStatus());
        assertEquals(12, pushed.getTimeUsed());
    }

    private SubmitRecord submitRecordWithHugeCode(String huge) {
        SubmitRecord submitRecord = submitRecord();
        submitRecord.setSubmitContent(huge);
        when(submitRecordMapper.selectById(555L)).thenReturn(submitRecord);
        return submitRecord;
    }

    private JudgeResultDto captureWebsocketResult() {
        ArgumentCaptor<Object> wsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.MESSAGE_EXCHANGE),
                eq(MqContexts.WEBSOCKET_ROUTING_KEY),
                wsCaptor.capture());
        WebsocketSendDto sendDto = (WebsocketSendDto) wsCaptor.getValue();
        assertEquals(WebsocketContexts.JUDGE_RESULT, sendDto.getQueueName());
        return (JudgeResultDto) sendDto.getResult();
    }

    private Message amqpMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        return new Message(new byte[0], properties);
    }

    private JudgeRecord judgeRecord() {
        JudgeRecord judgeRecord = new JudgeRecord();
        judgeRecord.setJudgeRecordId(JUDGE_RECORD_ID);
        judgeRecord.setSubmitRecordId(555L);
        judgeRecord.setSubmitStatus("WA");
        judgeRecord.setFailIndex(2);
        judgeRecord.setTimeUsed(12);
        judgeRecord.setMemoryUsed(2048);
        judgeRecord.setErrorMsg("wrong answer");
        judgeRecord.setLog("log-content");
        judgeRecord.setExpectedOutput("expected");
        judgeRecord.setUserOutput("actual");
        return judgeRecord;
    }

    private SubmitRecord submitRecord() {
        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitRecordId(555L);
        submitRecord.setQuestionId(9L);
        submitRecord.setUserId(42L);
        submitRecord.setLanguage("Python");
        submitRecord.setSubmitContent("print(1)");
        submitRecord.setJudgeStatus("judging");
        submitRecord.setSubmitScene("NORMAL");
        return submitRecord;
    }
}

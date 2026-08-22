package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SubmitRecordHandel 单测：消息信封/裸格式双读 + WebSocket 推送大字段截断。
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
    private Channel channel;

    @InjectMocks
    private SubmitRecordHandel handler;

    @BeforeEach
    void setUp() {
        when(judgeRecordMapper.selectById(JUDGE_RECORD_ID)).thenReturn(judgeRecord());
        when(submitRecordMapper.selectById(555L)).thenReturn(submitRecord());
        when(submitRecordMapper.updateById(any(SubmitRecord.class))).thenReturn(1);
        when(questionMapper.updateTotal(9L)).thenReturn(1);
        // 仅 AC 状态才会调用，宽松桩避免严格模式误报
        lenient().when(questionMapper.updateTotalAc(9L)).thenReturn(1);
    }

    @Test
    void readsJudgeRecordIdFromBareLongBody() throws Exception {
        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        verify(judgeRecordMapper).selectById(JUDGE_RECORD_ID);
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
    void truncatesOversizedFieldsOnlyForWebsocketPush() throws Exception {
        String huge = "x".repeat(SubmitRecordHandel.MAX_FIELD_LENGTH * 2);

        JudgeRecord judgeRecord = judgeRecord();
        judgeRecord.setLog(huge);
        judgeRecord.setExpectedOutput(huge);
        judgeRecord.setUserOutput(huge);
        when(judgeRecordMapper.selectById(JUDGE_RECORD_ID)).thenReturn(judgeRecord);

        SubmitRecord submitRecord = submitRecordWithHugeCode(huge);

        handler.handle(String.valueOf(JUDGE_RECORD_ID), channel, amqpMessage());

        // DB 更新使用原始完整数据：状态/耗时/内存取自判题记录，代码字段未被截断
        ArgumentCaptor<SubmitRecord> dbCaptor = ArgumentCaptor.forClass(SubmitRecord.class);
        verify(submitRecordMapper).updateById(dbCaptor.capture());
        SubmitRecord updated = dbCaptor.getValue();
        assertEquals("WA", updated.getSubmitStatus());
        assertEquals(12, updated.getTimeUsed());
        assertEquals(2048, updated.getMemoryUsed());
        assertEquals(huge.length(), updated.getSubmitContent().length());

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
        submitRecord.setJudgeStatus("pending");
        submitRecord.setSubmitScene("NORMAL");
        return submitRecord;
    }
}

package org.example.servicequestion.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.servicecommon.dto.TestMessage;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TestCaseHandle 单测：ACK 后置（事务提交后才确认）、库内去重、
 * AI 状态收尾入事务、失败单次 nack + Redis 计数重试。
 */
@ExtendWith(MockitoExtension.class)
class TestCaseHandleTest {

    private static final long QUESTION_ID = 9L;

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private Channel channel;

    @InjectMocks
    private TestCaseHandle handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 事务模板直接执行回调：模拟"事务体运行、提交后返回"
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(1L);
    }

    @Test
    void acksOnlyAfterTransactionCommits() throws Exception {
        when(testCaseMapper.selectList(any())).thenReturn(List.of());
        when(testCaseMapper.insert(any(TestCase.class))).thenReturn(1);

        handler.handle(body(testMessage()), channel, amqpMessage());

        verify(testCaseMapper).insert(any(TestCase.class));
        // AI 状态收尾在事务内、ACK 严格在事务提交之后
        InOrder inOrder = inOrder(transactionTemplate, questionMapper, channel);
        inOrder.verify(transactionTemplate).executeWithoutResult(any());
        inOrder.verify(questionMapper).updateAiStatusToSuccess(QUESTION_ID);
        inOrder.verify(channel).basicAck(1L, false);
    }

    @Test
    void skipsExistingDuplicateCasesButStillMarksSuccess() throws Exception {
        TestMessage message = testMessage();
        // 库内已有同指纹用例（同输入+同期望输出 -> 同 MD5）
        TestCase existing = new TestCase();
        existing.setQuestionId(QUESTION_ID);
        existing.setInputData(message.getInputData());
        existing.setExpectedOutput(message.getExpectedOutput());
        when(testCaseMapper.selectList(any())).thenReturn(List.of(existing));

        handler.handle(body(message), channel, amqpMessage());

        verify(testCaseMapper, never()).insert(any(TestCase.class));
        verify(questionMapper).updateAiStatusToSuccess(QUESTION_ID);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void acksEmptyMessageListWithoutTransaction() throws Exception {
        handler.handle("[]", channel, amqpMessage());

        verify(channel).basicAck(1L, false);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void nacksWithRequeueOnceWhenTransactionFails() throws Exception {
        when(testCaseMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        handler.handle(body(testMessage()), channel, amqpMessage());

        verify(hashOperations).increment(eq("question:testcase:retry-count"), anyString(), eq(1L));
        verify(hashOperations, never()).put(anyString(), any(), any());
        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(questionMapper, never()).updateAiStatusToSuccess(any());
    }

    @Test
    void recordsFailureAndDiscardsWhenRetryExhausted() throws Exception {
        when(testCaseMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(3L);

        String messageBody = body(testMessage());
        handler.handle(messageBody, channel, amqpMessage());

        verify(hashOperations).put(eq("question:testcase:failed"), anyString(), eq(messageBody));
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    private String body(TestMessage message) throws Exception {
        return objectMapper.writeValueAsString(List.of(message));
    }

    private TestMessage testMessage() {
        TestMessage testMessage = new TestMessage();
        testMessage.setQuestionId(QUESTION_ID);
        testMessage.setInputData("1 2");
        testMessage.setExpectedOutput("3");
        testMessage.setTimeLimit(1000);
        testMessage.setMemoryLimit(256);
        testMessage.setCreateUserId(42L);
        return testMessage;
    }

    private Message amqpMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        return new Message(new byte[0], properties);
    }
}

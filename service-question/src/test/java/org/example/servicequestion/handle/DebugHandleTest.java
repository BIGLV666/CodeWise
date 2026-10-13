package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.service.FunctionTestCaseGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DebugHandleTest {
    private static final String UUID_TEXT = "123e4567-e89b-12d3-a456-426614174000";

    @Mock RabbitTemplate rabbitTemplate;
    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> values;
    @Mock HashOperations<String, Object, Object> hashes;
    @Mock FunctionTestCaseGenerationService generationService;
    @Mock Channel channel;

    private DebugHandle handler;

    @BeforeEach
    void setUp() {
        handler = new DebugHandle();
        ReflectionTestUtils.setField(handler, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(handler, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(handler, "functionTestCaseGenerationService", generationService);
        handler.pendingBackoffMillis = 0;
        lenient().when(redisTemplate.opsForValue()).thenReturn(values);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashes);
        lenient().when(values.setIfAbsent(anyString(), eq("pending"), eq(5L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);
        lenient().when(generationService.isGenerationTask(UUID_TEXT)).thenReturn(false);
        lenient().when(hashes.get(anyString(), eq(UUID_TEXT))).thenReturn(List.of(result()));
        lenient().when(redisTemplate.delete(anyString())).thenReturn(true);
        lenient().when(channel.isOpen()).thenReturn(true);
    }

    @Test
    void acceptsBareQuotedAndOutboxEnvelopeUuid() throws Exception {
        for (String body : List.of(UUID_TEXT, "\"" + UUID_TEXT + "\"",
                EnvelopeCodec.serialize(EnvelopeCodec.wrap("DEBUG_RESULT", "judge", "trace", UUID_TEXT)))) {
            reset(values, hashes, rabbitTemplate, channel);
            when(values.setIfAbsent(anyString(), eq("pending"), eq(5L), eq(TimeUnit.MINUTES))).thenReturn(true);
            when(hashes.get(anyString(), eq(UUID_TEXT))).thenReturn(List.of(result()));
            when(generationService.isGenerationTask(UUID_TEXT)).thenReturn(false);
            handler.handle(body, channel, message(1L));
            verify(channel).basicAck(1L, false);
            verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
            clearInvocations(channel, rabbitTemplate);
        }
    }

    @Test
    void invalidOrBlankUuidIsDiscardedWithoutTouchingRedis() throws Exception {
        handler.handle("  ", channel, message(2L));
        handler.handle("not-a-uuid", channel, message(3L));
        verifyNoInteractions(redisTemplate);
        verify(channel).basicNack(2L, false, false);
        verify(channel).basicNack(3L, false, false);
    }

    @Test
    void pendingDuplicateIsRequeuedAndSuccessDuplicateIsAcknowledged() throws Exception {
        when(values.setIfAbsent(anyString(), eq("pending"), eq(5L), eq(TimeUnit.MINUTES))).thenReturn(false);
        when(values.get(RedisContext.QUESTION_SUCCESS_KEY + UUID_TEXT)).thenReturn("pending", "success");

        handler.handle(UUID_TEXT, channel, message(4L));
        handler.handle(UUID_TEXT, channel, message(5L));

        verify(channel).basicNack(4L, false, true);
        verify(channel).basicAck(5L, false);
        verifyNoInteractions(hashes, rabbitTemplate);
    }

    @Test
    void sendFailureReleasesPendingAndNacksWithoutThrowing() throws Exception {
        doThrow(new IllegalStateException("send")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        assertDoesNotThrow(() -> handler.handle(UUID_TEXT, channel, message(6L)));

        verify(redisTemplate).delete(RedisContext.QUESTION_SUCCESS_KEY + UUID_TEXT);
        verify(channel).basicNack(6L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void cleanupFailureAfterAckDoesNotNack() throws Exception {
        doThrow(new IllegalStateException("cleanup")).when(hashes)
                .delete(RedisContext.JUDGE_RESULT_KEY, UUID_TEXT);

        assertDoesNotThrow(() -> handler.handle(UUID_TEXT, channel, message(7L)));

        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void generationSuccessCompletesAndCleansDebugPayload() throws Exception {
        when(generationService.isGenerationTask(UUID_TEXT)).thenReturn(true);

        handler.handle(UUID_TEXT, channel, message(8L));

        verify(generationService).complete(eq(UUID_TEXT), anyList());
        verify(hashes).delete(RedisContext.JUDGE_RESULT_KEY, UUID_TEXT);
        verify(hashes).delete(RedisContext.JUDGE_DEBUG_KEY, UUID_TEXT);
        verify(channel).basicAck(8L, false);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void pendingDuplicateWaitsBeforeRequeue() throws Exception {
        handler.pendingBackoffMillis = 10;
        when(values.setIfAbsent(anyString(), eq("pending"), eq(5L), eq(TimeUnit.MINUTES))).thenReturn(false);
        when(values.get(RedisContext.QUESTION_SUCCESS_KEY + UUID_TEXT)).thenReturn("pending");

        long start = System.nanoTime();
        handler.handle(UUID_TEXT, channel, message(9L));

        org.junit.jupiter.api.Assertions.assertTrue((System.nanoTime() - start) >= 10_000_000L);
        verify(channel).basicNack(9L, false, true);
    }

    @Test
    void generationFailureIsPersistedAsTerminalFailureAndAcknowledged() throws Exception {
        when(generationService.isGenerationTask(UUID_TEXT)).thenReturn(true);
        doThrow(new IllegalStateException("generation")).when(generationService)
                .complete(eq(UUID_TEXT), anyList());

        handler.handle(UUID_TEXT, channel, message(10L));

        verify(generationService).fail(eq(UUID_TEXT), anyString());
        verify(channel).basicAck(10L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ackFailureDoesNotAttemptSecondSettlement() throws Exception {
        doThrow(new IllegalStateException("ack")).when(channel).basicAck(11L, false);

        assertDoesNotThrow(() -> handler.handle(UUID_TEXT, channel, message(11L)));

        verify(channel).basicAck(11L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel).abort();
    }

    @Test
    void reencodedEnvelopeUuidIsAccepted() throws Exception {
        // An envelope String pushed through a JSON message converter lands on the wire
        // as JSON-quoted JSON: a single unwrap only yields the inner envelope text.
        String envelope = EnvelopeCodec.serialize(
                EnvelopeCodec.wrap("DEBUG_RESULT", "judge", "trace", UUID_TEXT));
        String onTheWire = new ObjectMapper().writeValueAsString(envelope);

        handler.handle(onTheWire, channel, message(12L));

        verify(channel).basicAck(12L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void whitespacePaddedBareUuidIsAccepted() throws Exception {
        handler.handle("\n  " + UUID_TEXT + "  \t", channel, message(13L));

        verify(channel).basicAck(13L, false);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    private static JudgeReturnRecordDto result() {
        JudgeReturnRecordDto result = new JudgeReturnRecordDto();
        result.setUserId(9L);
        result.setSubmitStatus("AC");
        return result;
    }

    private static Message message(long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        return new Message(new byte[0], properties);
    }
}

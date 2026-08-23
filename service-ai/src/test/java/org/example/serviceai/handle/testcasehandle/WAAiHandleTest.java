package org.example.serviceai.handle.testcasehandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.repository.AiConversationRepository;
import org.example.serviceai.conversation.service.AdviceConversationService;
import org.example.serviceai.entry.ConsumedEvent;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.MessageStatus;
import org.example.serviceai.entry.Message;
import org.example.serviceai.service.AIService;
import org.example.serviceai.service.ConsumedEventService;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.serviceapi.dto.judge.JudgeContextDto;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WA 建议事件处理器单元测试：真重复跳过、正常链路（生成+结果引用+通知+完成）、
 * 通知失败上抛且不定为完成、重投恢复（result_ref 补发通知不重复生成）。
 */
@ExtendWith(MockitoExtension.class)
class WAAiHandleTest {

    @Mock
    private AIService aiService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private AiConversationRepository aiConversationRepository;
    @Mock
    private AdviceConversationService adviceConversationService;
    @Mock
    private QuestionFeignClient questionFeignClient;
    @Mock
    private ConsumedEventService consumedEventService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WAAiHandle handler;

    private static final String BODY = "{\"userId\":10,\"submitId\":1,\"questionId\":20,"
            + "\"judgeRecordId\":30,\"messageId\":\"evt-1\",\"language\":\"java\",\"judgeStatus\":\"WA\"}";

    /** 打桩判题上下文拉取（生成链路用例共用）。 */
    private void stubJudgeContext() {
        JudgeContextDto context = JudgeContextDto.builder()
                .judgeRecordId(30L)
                .questionId(20L)
                .code("code")
                .log("log")
                .inputData("in")
                .expectedOutput("out")
                .userOutput("wrong")
                .questionContent("question")
                .build();
        when(questionFeignClient.getJudgeContext(30L)).thenReturn(Result.success(context));
    }

    @Test
    void completedDuplicateSkipsEverything() throws Exception {
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.DUPLICATE_COMPLETED);

        handler.handle(BODY, amqpMessageOf(BODY));

        verifyNoInteractions(questionFeignClient, aiService, rabbitTemplate, aiConversationRepository);
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void freshEventUsesPersistedAdviceIdAsResultRef() throws Exception {
        stubJudgeContext();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.NEW);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.empty());
        when(aiConversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            saved.setConversationId(1L);
            return 1;
        });
        when(aiService.callAi(anyString())).thenReturn("建议内容");
        when(aiConversationRepository.appendMessage(anyLong(), any(Message.class)))
                .thenAnswer(invocation -> {
                    Message message = invocation.getArgument(1);
                    message.setMessageId(123L);
                    return message;
                });

        handler.handle(BODY, amqpMessageOf(BODY));

        // 正常链路：生成 → 落结果引用 → 发通知 → 完成
        verify(aiConversationRepository).save(any());
        verify(aiService).callAi(anyString());
        verify(consumedEventService).markResultRef("evt-1", 123L);
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY),
                any(NotificationDto.class));
        verify(consumedEventService).complete("evt-1");
        verify(consumedEventService, never()).markFailed(anyString(), anyString());
    }

    @Test
    void notificationFailurePropagatesWithoutCompleting() throws Exception {
        stubJudgeContext();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.NEW);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.empty());
        when(aiConversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            saved.setConversationId(1L);
            return 1;
        });
        when(aiService.callAi(anyString())).thenReturn("建议内容");
        when(aiConversationRepository.appendMessage(anyLong(), any(Message.class)))
                .thenAnswer(invocation -> {
                    Message message = invocation.getArgument(1);
                    message.setMessageId(123L);
                    return message;
                });
        doThrow(new IllegalStateException("notification broker down"))
                .when(rabbitTemplate).convertAndSend(
                eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY),
                any(NotificationDto.class));

        assertThrows(IllegalStateException.class, () -> handler.handle(BODY, amqpMessageOf(BODY)));

        // 通知未发出：事件绝不定为 COMPLETED，只记录失败等待重试
        verify(consumedEventService, never()).complete(anyString());
        verify(consumedEventService).markResultRef("evt-1", 123L);
        verify(consumedEventService).recordFailure(eq("evt-1"), anyString());
    }

    @Test
    void redeliveryWithResultRefResendsNotificationWithoutRegenerating() throws Exception {
        ConsumedEvent existing = ConsumedEvent.builder()
                .eventId("evt-1")
                .resultRef("123")
                .build();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.RECLAIM);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.of(existing));
        Message advice = Message.builder().messageId(123L).conversationId(1L).content("建议内容").build();
        when(aiConversationRepository.getMessage(123L)).thenReturn(advice);

        handler.handle(BODY, amqpMessageOf(BODY));

        // 不再拉判题上下文、不再建会话/生成，只重发通知并完成
        verifyNoInteractions(questionFeignClient, aiService);
        verify(aiConversationRepository, never()).save(any());
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY),
                any(NotificationDto.class));
        verify(consumedEventService).complete("evt-1");
    }

    @Test
    void missingJudgeRecordIdIsPoisonMessage() {
        String body = "{\"userId\":10,\"questionId\":20}";

        assertThrows(IllegalArgumentException.class, () -> handler.handle(body, amqpMessageOf(body)));
        verifyNoInteractions(questionFeignClient, aiService, rabbitTemplate);
    }

    @Test
    void insufficientInputMarksFailedWithoutNotifying() throws Exception {
        stubJudgeContext();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.NEW);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.empty());
        when(aiConversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            saved.setConversationId(1L);
            return 1;
        });
        when(aiService.callAi(anyString())).thenReturn("信息不全：缺少题目描述");

        handler.handle(BODY, amqpMessageOf(BODY));

        // 重试无意义：置终态 FAILED 留存，不发通知、不定为完成
        verify(consumedEventService).markFailed(eq("evt-1"), anyString());
        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(NotificationDto.class));
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void envelopeEventIdIsPreferredOverMessageId() throws Exception {
        String envelope = "{\"eventId\":\"env-9\",\"eventType\":\"AI_WA_ADVICE_REQUEST\","
                + "\"schemaVersion\":1,\"occurredAt\":\"2026-08-23T00:00:00\","
                + "\"producer\":\"service-judge\",\"traceId\":\"t-1\",\"payload\":"
                + "{\"userId\":10,\"submitId\":1,\"questionId\":20,\"judgeRecordId\":30,"
                + "\"messageId\":\"evt-1\",\"language\":\"java\",\"judgeStatus\":\"WA\"}}";
        when(consumedEventService.claim("env-9", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.DUPLICATE_COMPLETED);

        handler.handle(envelope, amqpMessageOf(envelope));

        verify(consumedEventService).claim(eq("env-9"), anyString());
    }

    @Test
    void reusedFollowUpRetriesWithoutNewSystemMessage() throws Exception {
        stubJudgeContext();
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);
        ConsumedEvent event = ConsumedEvent.builder()
                .eventId("evt-1")
                .createTime(since)
                .build();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.NEW);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.of(event));
        when(aiConversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            saved.setConversationId(1L);
            return -1;
        });
        Conversation existingConversation = Conversation.builder()
                .conversationId(1L)
                .userId(10L)
                .build();
        when(aiConversationRepository.findByUserIdAndQuestionId(10L, 20L)).thenReturn(existingConversation);
        when(adviceConversationService.findReusableFollowUpAssistant(1L, since)).thenReturn(77L);
        when(aiConversationRepository.getMessage(77L)).thenReturn(Message.builder()
                .messageId(77L).conversationId(1L).status(MessageStatus.FAILED).build());
        when(adviceConversationService.ask(eq(10L), eq(1L), anyString(), anyString(), eq(Role.SYSTEM), eq(77L)))
                .thenReturn(Message.builder()
                        .messageId(77L).conversationId(1L).content("建议内容")
                        .status(MessageStatus.COMPLETED).build());

        handler.handle(BODY, amqpMessageOf(BODY));

        // 复用路径：走 6 参重载复用 FAILED 行，不再新增 SYSTEM 追问（5 参 ask 不被调用）
        verify(adviceConversationService).ask(eq(10L), eq(1L), anyString(), anyString(), eq(Role.SYSTEM), eq(77L));
        verify(adviceConversationService, never()).ask(any(), any(), anyString(), anyString(), any());
        verify(consumedEventService).markResultRef("evt-1", 77L);
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY),
                any(NotificationDto.class));
        verify(consumedEventService).complete("evt-1");
    }

    @Test
    void completedFollowUpRecoveredWithoutRegeneration() throws Exception {
        stubJudgeContext();
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);
        ConsumedEvent event = ConsumedEvent.builder()
                .eventId("evt-1")
                .createTime(since)
                .build();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.NEW);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.of(event));
        when(aiConversationRepository.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            saved.setConversationId(1L);
            return -1;
        });
        Conversation existingConversation = Conversation.builder()
                .conversationId(1L)
                .userId(10L)
                .build();
        when(aiConversationRepository.findByUserIdAndQuestionId(10L, 20L)).thenReturn(existingConversation);
        when(adviceConversationService.findReusableFollowUpAssistant(1L, since)).thenReturn(77L);
        // 恢复窗口：AI 已成功但 result_ref 丢失（最后一条 ASSISTANT 已 COMPLETED）
        when(aiConversationRepository.getMessage(77L)).thenReturn(Message.builder()
                .messageId(77L).conversationId(1L).content("既有建议")
                .status(MessageStatus.COMPLETED).build());

        handler.handle(BODY, amqpMessageOf(BODY));

        // 不再生成（两种 ask 均不调用），只补结果引用与通知
        verify(adviceConversationService, never()).ask(any(), any(), anyString(), anyString(), any());
        verify(adviceConversationService, never()).ask(any(), any(), anyString(), anyString(), any(), any());
        verifyNoInteractions(aiService);
        verify(consumedEventService).markResultRef("evt-1", 77L);
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_AI_ADVICE_ROUTING_KEY),
                any(NotificationDto.class));
        verify(consumedEventService).complete("evt-1");
    }

    @Test
    void missingAdviceMessageMarksFailedTerminal() throws Exception {
        ConsumedEvent existing = ConsumedEvent.builder()
                .eventId("evt-1")
                .resultRef("123")
                .build();
        when(consumedEventService.claim("evt-1", handler.getRoutingKey()))
                .thenReturn(ConsumedEventService.ClaimOutcome.RECLAIM);
        when(consumedEventService.findByEventId("evt-1")).thenReturn(Optional.of(existing));
        when(aiConversationRepository.getMessage(123L)).thenReturn(null);

        handler.handle(BODY, amqpMessageOf(BODY));

        // 建议消息已被删除：重试无法恢复，终态留痕后放行（ACK），不空转重试
        verify(consumedEventService).markFailed("evt-1", "advice message missing");
        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(NotificationDto.class));
        verify(consumedEventService, never()).complete(anyString());
    }

    @Test
    void missingEventIdIsPoisonMessage() {
        // 裸格式缺 messageId：无幂等键可用，按毒消息死信（不 claim）
        String body = "{\"userId\":10,\"judgeRecordId\":30}";

        assertThrows(IllegalArgumentException.class, () -> handler.handle(body, amqpMessageOf(body)));
        verify(consumedEventService, never()).claim(anyString(), anyString());
    }

    private org.springframework.amqp.core.Message amqpMessageOf(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        return new org.springframework.amqp.core.Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

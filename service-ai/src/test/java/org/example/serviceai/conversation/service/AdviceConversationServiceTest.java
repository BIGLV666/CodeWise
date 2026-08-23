package org.example.serviceai.conversation.service;

import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.repository.AiConversationRepository;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;
import org.example.serviceai.entry.MessageStatus;
import org.example.serviceai.service.AIService;
import org.example.serviceai.service.UserAiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdviceConversationServiceTest {

    @Mock
    private AiConversationRepository aiConversationRepository;
    @Mock
    private AIService aiService;
    @Mock
    private AiConversationMemaryService aiConversationMemaryService;
    @Mock
    private UserAiService userAiService;
    @InjectMocks
    private AdviceConversationService service;

    private final AtomicLong idSequence = new AtomicLong(100L);

    @BeforeEach
    void setUpConversation() {
        Conversation conversation = Conversation.builder()
                .conversationId(1L)
                .userId(10L)
                .questionId(20L)
                .questionContent("question")
                .build();
        // lenient：cancelGeneratingMessages 等用例不走完整生成链路，允许桩未消费
        lenient().when(aiConversationRepository.get(1L)).thenReturn(conversation);
        lenient().when(aiConversationRepository.getRecentMessages(1L, 6)).thenReturn(List.of());
        // appendMessage 模拟数据库自增主键回填，便于校验收尾更新的目标行
        lenient().when(aiConversationRepository.appendMessage(any(), any(Message.class)))
                .thenAnswer(invocation -> {
                    Message message = invocation.getArgument(1);
                    if (message.getMessageId() == null) {
                        message.setMessageId(idSequence.incrementAndGet());
                    }
                    return message;
                });
    }

    @Test
    void usesAutomaticProviderWhenCustomFieldsAreEmpty() {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("auto-answer");
            return null;
        }).when(aiService).streamAi(anyString(), any());

        Message result = service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        );

        assertEquals("auto-answer", result.getContent());
        verify(aiService).streamAi(anyString(), any());
        verify(userAiService, never()).streamAi(anyString(), any(), any(), any(), anyString());
    }

    @Test
    void usesOwnedCustomProviderWhenConfigAndModelArePresent() {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("custom-answer");
            return null;
        }).when(userAiService).streamAi(anyString(), any(), any(), any(), anyString());

        Message result = service.askStream(
                10L, 1L, "why", "code", Role.USER,
                99L, "custom-model", chunk -> { }
        );

        assertEquals("custom-answer", result.getContent());
        verify(userAiService).streamAi(anyString(), any(), any(), any(), anyString());
        verify(aiService, never()).streamAi(anyString(), any());
    }

    @Test
    void askStreamInsertsGeneratingRowBeforeStreaming() {
        List<String> appendSnapshots = new java.util.ArrayList<>();
        when(aiConversationRepository.appendMessage(any(), any(Message.class)))
                .thenAnswer(invocation -> {
                    Message message = invocation.getArgument(1);
                    // 落库瞬间的快照：ASSISTANT 行必须以 GENERATING + 空串插入
                    appendSnapshots.add(message.getRole() + ":" + message.getStatus()
                            + ":" + message.getContent());
                    if (message.getMessageId() == null) {
                        message.setMessageId(idSequence.incrementAndGet());
                    }
                    return message;
                });
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("partial");
            return null;
        }).when(aiService).streamAi(anyString(), any());

        Message result = service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        );

        // 第一行是用户消息，第二行 ASSISTANT 占位行必须以 GENERATING + 空串先落库
        assertEquals(List.of("USER:null:why", "ASSISTANT:GENERATING:"), appendSnapshots);
        assertEquals(MessageStatus.COMPLETED, result.getStatus());
    }

    @Test
    void askStreamMarksCompletedOnSuccess() {
        Long assistantId = ASSISTANT_ROW_ID;
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("full answer");
            return null;
        }).when(aiService).streamAi(anyString(), any());

        Message result = service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        );

        verify(aiConversationRepository).finishGeneratingMessage(
                assistantId, "full answer", MessageStatus.COMPLETED);
        assertEquals(MessageStatus.COMPLETED, result.getStatus());
        assertEquals("full answer", result.getContent());
    }

    @Test
    void askStreamMarksFailedOnProviderError() {
        Long assistantId = ASSISTANT_ROW_ID;
        doThrow(new RuntimeException("AI providers failed"))
                .when(aiService).streamAi(anyString(), any());

        assertThrows(RuntimeException.class, () -> service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        ));

        // 失败：无已生成内容时以空串收尾为 FAILED
        verify(aiConversationRepository).finishGeneratingMessage(
                assistantId, "", MessageStatus.FAILED);
    }

    @Test
    void askStreamMarksFailedKeepingPartialContent() {
        Long assistantId = ASSISTANT_ROW_ID;
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("partial ");
            throw new RuntimeException("AI providers failed");
        }).when(aiService).streamAi(anyString(), any());

        assertThrows(RuntimeException.class, () -> service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        ));

        verify(aiConversationRepository).finishGeneratingMessage(
                assistantId, "partial ", MessageStatus.FAILED);
    }

    @Test
    void askStreamMarksCancelledOnClientDisconnect() {
        Long assistantId = ASSISTANT_ROW_ID;
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("par");
            // 控制器包装 SSE send 失败的形态：RuntimeException 因链上带 IOException
            throw new RuntimeException("SSE 客户端已断开", new IOException("broken pipe"));
        }).when(aiService).streamAi(anyString(), any());

        assertThrows(RuntimeException.class, () -> service.askStream(
                10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }
        ));

        verify(aiConversationRepository).finishGeneratingMessage(
                assistantId, "par", MessageStatus.CANCELLED);
    }

    @Test
    void cancelGeneratingMessagesDelegatesToRepository() {
        when(aiConversationRepository.cancelGeneratingMessages(1L)).thenReturn(1);

        int cancelled = service.cancelGeneratingMessages(1L);

        assertEquals(1, cancelled);
        verify(aiConversationRepository).cancelGeneratingMessages(1L);
    }

    @Test
    void askMarksCompletedOnSuccess() {
        when(aiService.callAi(anyString())).thenReturn("reply");

        Message result = service.ask(10L, 1L, "why", "code", Role.USER);

        Long assistantId = result.getMessageId();
        verify(aiConversationRepository).finishGeneratingMessage(
                assistantId, "reply", MessageStatus.COMPLETED);
        assertEquals(MessageStatus.COMPLETED, result.getStatus());
        assertEquals("reply", result.getContent());
    }

    @Test
    void askMarksFailedAndRethrowsOnProviderError() {
        when(aiService.callAi(anyString())).thenThrow(new RuntimeException("AI providers failed"));

        assertThrows(RuntimeException.class, () -> service.ask(10L, 1L, "why", "code", Role.USER));

        verify(aiConversationRepository).finishGeneratingMessage(
                any(), eq(""), eq(MessageStatus.FAILED));
    }

    @Test
    void askThrowsIllegalStateWhenConversationMissing() {
        when(aiConversationRepository.get(1L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.ask(10L, 1L, "why", "code", Role.USER));
    }

    @Test
    void askThrowsIllegalStateForForeignConversation() {
        assertThrows(IllegalStateException.class,
                () -> service.ask(99L, 1L, "why", "code", Role.USER));
    }

    @Test
    void askStreamThrowsIllegalStateWhenConversationMissing() {
        when(aiConversationRepository.get(1L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.askStream(
                10L, 1L, "why", "code", Role.USER, null, null, chunk -> { }));
    }

    @Test
    void askStreamThrowsIllegalStateForForeignConversation() {
        assertThrows(IllegalStateException.class, () -> service.askStream(
                99L, 1L, "why", "code", Role.USER, null, null, chunk -> { }));
    }

    @Test
    void askReusesFailedRowWithoutInsertingMessages() {
        Message prior = Message.builder()
                .messageId(77L).conversationId(1L).userId(10L)
                .status(MessageStatus.FAILED).build();
        when(aiConversationRepository.getMessage(77L)).thenReturn(prior);
        when(aiConversationRepository.restartFailedGeneration(77L)).thenReturn(true);
        when(aiService.callAi(anyString())).thenReturn("reply");

        Message result = service.ask(10L, 1L, "why", "code", Role.SYSTEM, 77L);

        // 复用路径：不新增任何消息行，重置 FAILED 行后原行收尾
        verify(aiConversationRepository, never()).appendMessage(any(), any(Message.class));
        verify(aiConversationRepository).restartFailedGeneration(77L);
        verify(aiConversationRepository).finishGeneratingMessage(77L, "reply", MessageStatus.COMPLETED);
        assertEquals("reply", result.getContent());
        assertEquals(MessageStatus.COMPLETED, result.getStatus());
    }

    @Test
    void askReuseThrowsIllegalStateWhenRowMissing() {
        when(aiConversationRepository.getMessage(77L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.ask(10L, 1L, "why", "code", Role.SYSTEM, 77L));
    }

    @Test
    void findReusableFollowUpAssistantReturnsNullWhenNoSystemMessageSince() {
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(aiConversationRepository.existsMessageByRoleSince(1L, Role.SYSTEM, since))
                .thenReturn(false);

        assertNull(service.findReusableFollowUpAssistant(1L, since));

        verify(aiConversationRepository, never()).findLastMessageByRoleSince(any(), any(), any());
    }

    @Test
    void findReusableFollowUpAssistantReturnsLastAssistantId() {
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);
        when(aiConversationRepository.existsMessageByRoleSince(1L, Role.SYSTEM, since))
                .thenReturn(true);
        when(aiConversationRepository.findLastMessageByRoleSince(1L, Role.ASSISTANT, since))
                .thenReturn(Message.builder().messageId(88L).build());

        assertEquals(88L, service.findReusableFollowUpAssistant(1L, since));
    }

    @Test
    void cancelGeneratingMessageDelegatesPerMessage() {
        when(aiConversationRepository.cancelGeneratingMessage(102L)).thenReturn(true);

        assertTrue(service.cancelGeneratingMessage(102L));

        // 只取消本流消息：不按会话批量取消，避免误杀同会话并发 SSE 流
        verify(aiConversationRepository).cancelGeneratingMessage(102L);
        verify(aiConversationRepository, never()).cancelGeneratingMessages(any());
    }

    @Test
    void askStreamInvokesAssistantCreatedCallback() {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("ok");
            return null;
        }).when(aiService).streamAi(anyString(), any());
        AtomicReference<Long> created = new AtomicReference<>();

        service.askStream(10L, 1L, "why", "code", Role.USER,
                null, null, chunk -> { }, created::set);

        // 占位行落库后回调其 messageId（101L=用户消息，102L=ASSISTANT 占位行）
        assertEquals(102L, created.get());
    }

    /** 两条 appendMessage（101L=用户消息，102L=ASSISTANT 占位行）中 ASSISTANT 行的主键。 */
    private static final Long ASSISTANT_ROW_ID = 102L;
}

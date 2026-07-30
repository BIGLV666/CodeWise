package org.example.serviceai.conversation.service;

import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.repository.AiConversationRepository;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;
import org.example.serviceai.service.AIService;
import org.example.serviceai.service.UserAiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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

    @BeforeEach
    void setUpConversation() {
        Conversation conversation = Conversation.builder()
                .conversationId(1L)
                .userId(10L)
                .questionId(20L)
                .questionContent("question")
                .build();
        when(aiConversationRepository.get(1L)).thenReturn(conversation);
        when(aiConversationRepository.getRecentMessages(1L, 6)).thenReturn(List.of());
        when(aiConversationRepository.appendMessage(any(), any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
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
}

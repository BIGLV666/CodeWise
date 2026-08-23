package org.example.serviceai.conversation.repository;

import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;
import org.example.serviceai.entry.MessageStatus;


import java.time.LocalDateTime;
import java.util.List;

public interface AiConversationRepository {
    Conversation get(Long conversationId);
    Message appendMessage(Long conversationId, Message message);
    Message getMessage(Long messageId);
    boolean finishGeneratingMessage(Long messageId, String content, MessageStatus finalStatus);
    boolean restartFailedGeneration(Long messageId);
    int cancelGeneratingMessages(Long conversationId);
    boolean cancelGeneratingMessage(Long messageId);
    boolean existsMessageByRoleSince(Long conversationId, Role role, LocalDateTime since);
    Message findLastMessageByRoleSince(Long conversationId, Role role, LocalDateTime since);
    List<Message> getRecentMessages(Long conversationId, int limit);
    List<Message> getMessagesBefore(Long conversationId, Long cursor, int limit);
    int save(Conversation conversation);
    List<HomeConversationVo> findAllByUserId(Long userId);
    Conversation findByUserIdAndQuestionId(Long userId, Long questionId);

}

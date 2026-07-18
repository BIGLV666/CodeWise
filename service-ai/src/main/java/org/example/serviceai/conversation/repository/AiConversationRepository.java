package org.example.serviceai.conversation.repository;

import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;


import java.util.List;

public interface AiConversationRepository {
    Conversation get(Long conversationId);
    Message appendMessage(Long conversationId, Message message);
    List<Message> getRecentMessages(Long conversationId, int limit);
    List<Message> getMessagesBefore(Long conversationId, Long cursor, int limit);
    int save(Conversation conversation);
    List<HomeConversationVo> findAllByUserId(Long userId);
    Conversation findByUserIdAndQuestionId(Long userId, Long questionId);

}

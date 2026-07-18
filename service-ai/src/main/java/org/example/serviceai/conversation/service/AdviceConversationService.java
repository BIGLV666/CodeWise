package org.example.serviceai.conversation.service;

import org.example.serviceai.conversation.dto.CursorPageResult;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.AiConversationMemory;

import org.example.serviceai.conversation.repository.AiConversationRepository;
import org.example.serviceai.entry.Message;
import org.example.serviceai.service.AIService;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
public class AdviceConversationService {
    @Autowired
    private AiConversationRepository aiConversationRepository;
    @Autowired
    private AIService aiService;
    @Autowired
    private AiConversationMemaryService  aiConversationMemaryService;
    public Message ask(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role type
    ) {
        // 校验会话归属
        Conversation conversation = aiConversationRepository.get(conversationId);
        if(conversation ==null){
            throw new IllegalArgumentException("未找到该会话");
        }
        if(!conversation.getUserId().equals(userId)){
            throw new IllegalArgumentException("无权操作他人会话");
        }
        // 构建 prompt
        String prompt= AdvicePromptBuilder.buildFollowUp(
                conversation,
                aiConversationRepository.getRecentMessages(conversationId,6),
                question,
                currentCode,
                aiConversationMemaryService.getMemory(conversationId, userId)

        );
        // 保存用户消息
        Message aiMessage = new Message();
        aiMessage.setConversationId(conversationId);
        aiMessage.setCurrentCode(currentCode);
        aiMessage.setRole(type);
        aiMessage.setUserId(userId);
        aiMessage.setContent(question);
        aiMessage.setCreateTime(LocalDateTime.now());
        aiConversationRepository.appendMessage(conversationId,aiMessage);
        // 调用 AI// 保存 AI 回答
        String repost=aiService.callAi(prompt);
        Message message=new Message();
        message.setConversationId(conversationId);
        message.setContent(repost);
        message.setUserId(userId);
        message.setRole(Role.ASSISTANT);
        message.setCreateTime(LocalDateTime.now());
        aiConversationRepository.appendMessage(conversationId,message);
        CompletableFuture.runAsync(()->{
            aiConversationMemaryService.updateSummery(conversationId, message.getUserId());
        });
        return message;

    }

    public List<HomeConversationVo> getConversations(){

        return aiConversationRepository.findAllByUserId(UserContext.getUserId());
    }
    //游标分页
    public CursorPageResult<Message> getMessages(
            Long userId,
            Long conversationId,
            Long cursor,
            int size
    ) {
        Conversation conversation = aiConversationRepository.get(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权查看他人会话");
        }

        int pageSize = Math.max(1, Math.min(size, 50));
        List<Message> queried = aiConversationRepository.getMessagesBefore(
                conversationId, cursor, pageSize + 1
        );
        boolean hasMore = queried.size() > pageSize;
        List<Message> page = new ArrayList<>(
                hasMore ? queried.subList(0, pageSize) : queried
        );
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getMessageId();
        Collections.reverse(page);
        return new CursorPageResult<>(page, nextCursor, hasMore);
    }
    public Message askStream(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role role,
            Consumer<String> onChunk
    ) {
        Conversation conversation =
                aiConversationRepository.get(conversationId);

        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该会话");
        }

        String prompt = AdvicePromptBuilder.buildFollowUp(
                conversation,
                aiConversationRepository.getRecentMessages(
                        conversationId,
                        6
                ),
                question,
                currentCode,
                aiConversationMemaryService.getMemory(conversationId, userId)
        );

        Message userMessage = new Message();
        userMessage.setConversationId(conversationId);
        userMessage.setUserId(userId);
        userMessage.setRole(role);
        userMessage.setContent(question);
        userMessage.setCurrentCode(currentCode);
        userMessage.setCreateTime(LocalDateTime.now());

        aiConversationRepository.appendMessage(
                conversationId,
                userMessage
        );

        StringBuilder fullAnswer = new StringBuilder();

        aiService.streamAi(prompt, chunk -> {
            fullAnswer.append(chunk);
            onChunk.accept(chunk);
        });

        Message assistantMessage = new Message();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(Role.ASSISTANT);
        assistantMessage.setContent(fullAnswer.toString());
        assistantMessage.setCurrentCode(currentCode);
        assistantMessage.setCreateTime(LocalDateTime.now());

        return aiConversationRepository.appendMessage(
                conversationId,
                assistantMessage
        );
    }

}

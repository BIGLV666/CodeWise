package org.example.serviceai.conversation.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;
import org.example.serviceai.entry.MessageStatus;
import org.example.serviceai.mapper.ConversationMapper;
import org.example.serviceai.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Repository
public class MybatisAiConversationRepository implements AiConversationRepository {
    @Autowired
    private MessageMapper messageMapper;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    @Override
    public Conversation get(Long conversationId) {
        return conversationMapper.selectById(conversationId);
    }

    @Override
    public Message appendMessage(Long conversationId, Message message) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if(conversation==null){
            throw new IllegalArgumentException("该会话不存在");
        }
        if(!conversation.getUserId().equals(message.getUserId())){
            throw new IllegalArgumentException("无权操作他人记忆");
        }
        int r= messageMapper.insert(message);
        if(r==0){
            throw new IllegalArgumentException("添加失败");
        }
        return message;
    }

    @Override
    public Message getMessage(Long messageId) {
        return messageMapper.selectById(messageId);
    }

    @Override
    public boolean finishGeneratingMessage(Long messageId, String content, MessageStatus finalStatus) {
        // 仅 GENERATING 行允许收尾（原子防覆盖）：已完成/已取消的行不被迟到回调覆盖
        int updated = messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getMessageId, messageId)
                .eq(Message::getStatus, MessageStatus.GENERATING)
                .set(Message::getContent, content)
                .set(Message::getStatus, finalStatus));
        return updated == 1;
    }

    @Override
    public boolean restartFailedGeneration(Long messageId) {
        // 失败行重置回生成中（WHERE status='FAILED' 原子守护）：GENERATING 行原样复用，COMPLETED/CANCELLED 不动
        int updated = messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getMessageId, messageId)
                .eq(Message::getStatus, MessageStatus.FAILED)
                .set(Message::getStatus, MessageStatus.GENERATING));
        return updated == 1;
    }

    @Override
    public int cancelGeneratingMessages(Long conversationId) {
        // SSE 超时/断开的兜底收尾：把会话内仍在生成的 ASSISTANT 行置为 CANCELLED
        return messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getStatus, MessageStatus.GENERATING)
                .set(Message::getStatus, MessageStatus.CANCELLED));
    }

    @Override
    public boolean cancelGeneratingMessage(Long messageId) {
        // 单消息取消（SSE 超时精准收尾，不影响同会话其他生成中的行）
        int updated = messageMapper.update(null, Wrappers.<Message>lambdaUpdate()
                .eq(Message::getMessageId, messageId)
                .eq(Message::getStatus, MessageStatus.GENERATING)
                .set(Message::getStatus, MessageStatus.CANCELLED));
        return updated == 1;
    }

    @Override
    public boolean existsMessageByRoleSince(Long conversationId, Role role, LocalDateTime since) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRole, role)
                .ge(Message::getCreateTime, since));
        return count != null && count > 0;
    }

    @Override
    public Message findLastMessageByRoleSince(Long conversationId, Role role, LocalDateTime since) {
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRole, role)
                .ge(Message::getCreateTime, since)
                .orderByDesc(Message::getMessageId)
                .last("LIMIT 1"));
    }

    @Override
    public List<Message> getRecentMessages(Long conversationId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));

        List<Message> messages = messageMapper.selectList(
                new QueryWrapper<Message>()
                        .eq("conversation_id", conversationId)
                        .orderByDesc("message_id")
                        .last("LIMIT " + safeLimit)
        );
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<Message> getMessagesBefore(Long conversationId, Long cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 51));
        QueryWrapper<Message> query = new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .orderByDesc("message_id")
                .last("LIMIT " + safeLimit);
        if (cursor != null) {
            query.lt("message_id", cursor);
        }
        return messageMapper.selectList(query);
    }

    @Override
    public int save(Conversation conversation) {
        try{
            return conversationMapper.insert(conversation);
        }catch (DuplicateKeyException e){
            return -1;
        }
    }

    @Override
    public List<HomeConversationVo> findAllByUserId(Long userId) {
        List<Conversation>list=conversationMapper.selectList(new QueryWrapper<Conversation>().eq("user_id",userId));
        List<HomeConversationVo> homeConversationVoList=new ArrayList<>();
        for(Conversation conversation:list){
            homeConversationVoList.add(new HomeConversationVo(conversation));
        }
        return homeConversationVoList;
    }

    @Override
    public Conversation findByUserIdAndQuestionId(Long userId, Long questionId) {
        return conversationMapper.selectOne(new QueryWrapper<Conversation>().eq("user_id",userId).eq("question_id",questionId));
    }


}

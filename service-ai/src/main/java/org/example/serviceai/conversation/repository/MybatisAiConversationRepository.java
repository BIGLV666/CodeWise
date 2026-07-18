package org.example.serviceai.conversation.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.Message;
import org.example.serviceai.mapper.ConversationMapper;
import org.example.serviceai.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

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

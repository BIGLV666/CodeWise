package org.example.serviceai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.serviceai.entry.Conversation;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}

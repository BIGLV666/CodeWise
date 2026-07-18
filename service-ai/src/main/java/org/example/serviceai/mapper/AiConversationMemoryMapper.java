package org.example.serviceai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.serviceai.entry.AiConversationMemory;

@Mapper
public interface AiConversationMemoryMapper extends BaseMapper<AiConversationMemory> {
}

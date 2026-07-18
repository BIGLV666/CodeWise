package org.example.serviceai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.serviceai.entry.Message;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}

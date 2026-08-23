package org.example.servicemessage.consumedevent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.servicemessage.consumedevent.entry.ConsumedEvent;

@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEvent> {
}

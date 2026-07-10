package org.example.servicecommunity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicecommunity.entry.Tags;

import java.util.List;

@Mapper
public interface TagsMapper extends BaseMapper<Tags> {
    int batchInsert(@Param("list") List<Tags> list);

    List<Tags> batchSelectForPostId(@Param("postIds") List<Long> postIds);

    List<Tags> BatchSelectPostFromTagName(@Param("tags") List<Tags> tags);
}

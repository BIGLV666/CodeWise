package org.example.servicecommunity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicecommunity.entry.Comment;

import java.util.Map;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    int updateLikeCount(@Param("likeMap") Map<Object, Object> likeMap);
}

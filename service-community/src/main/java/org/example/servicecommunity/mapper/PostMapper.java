package org.example.servicecommunity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicecommunity.entry.Post;

import java.util.List;
import java.util.Map;

@Mapper
public interface PostMapper extends BaseMapper<Post> {
    List<Post> BatchSelectTitlesForId(@Param("postIds") List<Long> postIds);
    int updateCommentTotal(@Param("count")Integer count,@Param("postId")Long postId);
    int updateLikeCount(@Param("likeMap") Map<Object, Object> likeMap);
}

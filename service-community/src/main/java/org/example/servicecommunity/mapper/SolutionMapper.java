package org.example.servicecommunity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicecommunity.entry.Solution;

import java.util.Map;

@Mapper
public interface SolutionMapper extends BaseMapper<Solution> {
    int updateCommentTotal(@Param("count") Integer count, @Param("solutionId") Long solutionId);

    int updateLookCount(@Param("lookMap") Map<Object, Object> lookMap);
    int updateLikeCount(@Param("likeMap") Map<Object, Object> likeMap);
}

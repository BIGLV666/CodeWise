package org.example.servicecommunity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicecommunity.entry.LikeRecord;

import java.util.List;
import java.util.Map;

@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecord> {
    @MapKey("postId")
    Map<Long,LikeRecord> selectBatchIdsForUserId(@Param("commentIds") List<Long> commentIds,
                                                 @Param("type")String type,
                                                 @Param("userId") Long userId);
}

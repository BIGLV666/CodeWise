package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import org.example.servicereview.entry.ReviewRecord;

@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {
    ReviewRecord getTodayRecord(@Param("userId") Long userId);
    ReviewRecord getlastRecord(@Param("userId") Long userId);
}

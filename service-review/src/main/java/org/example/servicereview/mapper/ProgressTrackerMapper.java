package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.vo.ProgressCalendarVo;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ProgressTrackerMapper extends BaseMapper<ProgressTracker> {

    /**
     * 按日期聚合区间内每天的计划题数与完成数。
     * completed 统计 status=2（COMPLETED），编码见 ProgressTrackerStatus。
     */
    @Select("""
            SELECT begin_time AS date,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS completed
            FROM progress_tracker
            WHERE user_id = #{userId} AND begin_time >= #{start} AND begin_time <= #{end}
            GROUP BY begin_time
            """)
    List<ProgressCalendarVo> aggregateByDateRange(@Param("userId") Long userId,
                                                  @Param("start") LocalDate start,
                                                  @Param("end") LocalDate end);
}

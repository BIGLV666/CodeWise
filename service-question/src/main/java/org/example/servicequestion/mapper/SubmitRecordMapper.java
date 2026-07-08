package org.example.servicequestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicequestion.entry.SubmitRecord;

import java.util.List;
@Mapper
public interface SubmitRecordMapper extends BaseMapper<SubmitRecord> {
    /**
     * 批量查询当前用户在指定题目中的提交状态。
     * 每道题最多返回一条聚合记录：
     * - submitStatus = AC：用户该题至少有一次 AC
     * - submitStatus = TRIED：用户该题提交过，但没有 AC
     * 未返回的题目表示用户从未尝试。
     */
    List<SubmitRecord> getQuestionSubmitStatusByQuestionIds(@Param("userId") Long userId, @Param("questionIds") List<Long> questionIds);
}

package org.example.servicejudge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.example.servicejudge.entry.SubmitRecord;


@Mapper
public interface SubmitRecordMapper extends BaseMapper<SubmitRecord> {
    @Update(value = "UPDATE submit_record SET judge_status = 'judging' WHERE submit_record_id = #{id} AND judge_status = 'pending';")
    int updateRecordToJudge(Long id);
    @Update(value = "UPDATE submit_record SET judge_status = 'pending' WHERE submit_record_id = #{id} AND judge_status = 'judging';")
    int updateRecordToPending(Long id);
    @Update("""
    UPDATE submit_record
    SET judge_status = 'failure'
    WHERE submit_record_id = #{id}
      AND judge_status = 'judging'
""")
    int updateRecordToFailure(Long id);

}

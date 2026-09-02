package org.example.serviceuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.example.serviceuser.entry.UserAppeal;
@Mapper
public interface UserAppealMapper extends BaseMapper<UserAppeal> {
    @Update("UPDATE user_appeal SET status = #{status}, process_result = #{processResult}, operator_id = #{operatorId}, update_time = #{updateTime} WHERE user_appeal_id = #{userAppealId} and status=0")
    int updateAppeal(Integer status, String processResult, Long operatorId, java.time.LocalDateTime updateTime, Long userAppealId);
}

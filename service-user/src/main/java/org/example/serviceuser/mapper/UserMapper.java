package org.example.serviceuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.example.serviceuser.entry.User;

import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Update("UPDATE user SET status = #{status}, ban_time = #{banTime} WHERE user_id = #{userId} and status=1")
    int updateStatus(Long userId, int status, LocalDateTime banTime);
}

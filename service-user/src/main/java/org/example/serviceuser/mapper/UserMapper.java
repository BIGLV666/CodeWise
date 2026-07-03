package org.example.serviceuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.serviceuser.entry.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}

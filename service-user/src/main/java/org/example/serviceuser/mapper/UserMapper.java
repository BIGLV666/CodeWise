package org.example.serviceuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.serviceuser.entry.User;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    @Update("UPDATE user SET status = #{status}, ban_time = #{banTime} WHERE user_id = #{userId} and status=1")
    int updateStatus(Long userId, int status, LocalDateTime banTime);

    /**
     * 按昵称或用户名模糊查找用户（命中其一即可），返回按匹配度排序的列表。
     *
     * <p>排序规则：用户名精确命中优先，其次昵称精确命中，再按 user_id 兜底保证顺序稳定。
     * 模糊搜索可能命中大量用户，这里用 {@code LIMIT 50} 兜底，避免一次拉回整张表。</p>
     */
    @Select("""
            SELECT * FROM `user`
            WHERE #{name} IS NOT NULL
              AND #{name} <> ''
              AND (user_name LIKE CONCAT('%', #{name}, '%')
                   OR nick_name LIKE CONCAT('%', #{name}, '%'))
            ORDER BY (user_name = #{name}) DESC, (nick_name = #{name}) DESC, user_id
            LIMIT 50
            """)
    List<User> findUserByName(String name);
}

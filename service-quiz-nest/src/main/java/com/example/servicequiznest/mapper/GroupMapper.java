package com.example.servicequiznest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.enums.GroupMemberRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    int updateGroup(Group group);

    /**
     * 修改root用户，如果退出的为root,并指定新root，假如该root是最后一名成员则删除该group
     */
    int rootLeaveGroup(Long groupId,Long userId);
    /**
     * 普通用户退出，无需修改root
     */
    int leaveGroup(Long groupId, Long userId);
}

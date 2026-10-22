package com.example.servicequiznest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.servicequiznest.entity.Group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    int updateGroup(Group group);

    /**
     * 普通成员/待审批成员退出：删除自己的成员记录。
     */
    int leaveGroup(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 为小组成员数加 1（新成员加入 APPROVED 状态时调用）。
     */
    int addMemberCount(@Param("groupId") Long groupId);

    /**
     * 为小组成员数减 1，乐观锁防成员数为负。
     */
    int decMemberCount(@Param("groupId") Long groupId);

    /**
     * 晋升第一步：把 root 指针（group.user_id）CAS 到继承人，只有仍指向退出者时才成功。
     * 命中 0 行说明并发中已被别人改过，调用方必须整体回滚。
     */
    int casRootOwner(@Param("groupId") Long groupId, @Param("oldRoot") Long oldRoot, @Param("newRoot") Long newRoot);

    /**
     * 晋升第二步：把继承人的成员记录升为 ROOT。
     */
    int promoteMemberToRoot(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 删除退出者本人的 ROOT 成员记录。
     */
    int deleteRootMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 选继承人：ADMIN 优先，其次最近有成员记录变更，最后按入库顺序兜底；无候选返回 null。
     */
    Long selectHeirUserId(@Param("groupId") Long groupId, @Param("leaverId") Long leaverId);

    /**
     * 本组已无 APPROVED 成员则删除小组，返回 0 表示组里还有别人（此时调用方应回滚）。
     */
    int deleteGroupIfNoApprovedMember(@Param("groupId") Long groupId);

    /**
     * 删除本组全部成员记录（最后一人退出时连 PENDING/REJECTED 占位一起清理）。
     */
    int deleteAllMembers(@Param("groupId") Long groupId);
}

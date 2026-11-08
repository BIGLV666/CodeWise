package com.example.servicequiznest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.vo.UserGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    /**
     * 把已有的 PENDING/REJECTED 占位记录原子地改为 APPROVED。
     *
     * <p>条件 {@code join_status <> 'APPROVED'} 保证并发加入（或并发审批）时只有
     * 一个事务能完成状态迁移，返回 1 的调用方才有资格给 member_count 加一；
     * 命中 0 行说明已被别人改成 APPROVED，调用方应返回「已加入」而不是重复计数。</p>
     */
    int approveExistingMember(@Param("groupId") Long groupId, @Param("userId") Long userId);


    int approveTOREJECTED(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 把被拒绝的记录原子地改回 PENDING（重新申请）。
     *
     * <p>只在 {@code join_status = 'REJECTED'} 时生效，避免并发下把刚通过的
     * APPROVED 记录又改回待审批。</p>
     */
    int reapplyRejectedMember(@Param("groupId") Long groupId, @Param("userId") Long userId);


    List<UserGroup>getAllUserGroup(@Param("userId")Long userId);

    /**
     * 设置/取消管理员：只更新 APPROVED 且非 ROOT 的成员记录。
     *
     * @param role 目标角色 name()（ADMIN / MEMBER）
     * @return 影响行数；0 表示目标不存在、未通过审批或已是 ROOT
     */
    int updateMemberRole(@Param("groupId") Long groupId,
                         @Param("userId") Long userId,
                         @Param("role") String role);

    /**
     * 移除（踢出）一名 APPROVED 非 ROOT 成员。
     *
     * @return 影响行数；0 表示目标已不在组内或为 ROOT
     */
    int deleteMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
}

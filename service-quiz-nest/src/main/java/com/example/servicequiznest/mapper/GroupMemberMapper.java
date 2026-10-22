package com.example.servicequiznest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.servicequiznest.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 把被拒绝的记录原子地改回 PENDING（重新申请）。
     *
     * <p>只在 {@code join_status = 'REJECTED'} 时生效，避免并发下把刚通过的
     * APPROVED 记录又改回待审批。</p>
     */
    int reapplyRejectedMember(@Param("groupId") Long groupId, @Param("userId") Long userId);
}

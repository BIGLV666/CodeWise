package com.example.servicequiznest.vo;

import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;

import java.time.LocalDateTime;

/**
 * 当前用户与某个小组的关系。
 *
 * <p>加入接口直接返回该 VO，前端据此区分「已加入（APPROVED）」和
 * 「申请已提交（PENDING）」；成员管理页也用它获取当前用户的角色，
 * 不再通过题库接口的 404 间接探测身份。</p>
 *
 * @param groupId       小组 ID
 * @param groupMemberId 成员记录 ID
 * @param userId        用户 ID
 * @param role          组内角色
 * @param joinStatus    加入状态
 * @param joinTime      加入时间（PENDING 时为空）
 */
public record GroupMembershipVo(
        Long groupId,
        Long groupMemberId,
        Long userId,
        GroupMemberRole role,
        GroupJoinStatus joinStatus,
        LocalDateTime joinTime
) {
}

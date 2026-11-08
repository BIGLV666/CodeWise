package com.example.servicequiznest.vo;


import com.example.servicequiznest.enums.GroupMemberRole;

import java.time.LocalDateTime;

/**
 * 用户所在小组列表
 * @param groupId 小组id
 * @param groupName 小组名
 * @param role 该用户于对应小组的身份
 * @param memberCount 该小组人数
 * @param joinTime 用户加入时间
 */
public record UserGroup(
        Long groupId,
        String groupName,
        GroupMemberRole role,
        Long memberCount,
        LocalDateTime joinTime

) {
}

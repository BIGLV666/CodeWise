package com.example.servicequiznest.vo;

import com.example.servicequiznest.enums.GroupMemberRole;

import java.time.LocalDateTime;

public record MemberVO(
        Long groupId,
        Long groupMemberId,
        Long userId,
        String avatarUrl,
        String nickName,
        GroupMemberRole role,
        LocalDateTime joinTime
) {
}

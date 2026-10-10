package com.example.servicequiznest.enums;

import lombok.Getter;

/**
 * 小组成员角色枚举
 * MEMBER: 普通成员
 * ADMIN: 管理员
 * Root: 超级管理员即创始人
 */
@Getter
public enum GroupMemberRole {

    MEMBER(0, "普通成员"),
    ADMIN(1, "管理员"),
    ROOT(2, "超级管理员");

    private final int code;
    private final String description;

    GroupMemberRole(int code, String description) {
        this.code = code;
        this.description = description;
    }

}

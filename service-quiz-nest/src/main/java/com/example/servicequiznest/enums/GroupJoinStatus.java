package com.example.servicequiznest.enums;

import lombok.Getter;

/**
 * 小组加入状态枚举
 * PENDING: 待审批
 * APPROVED: 已加入
 * REJECTED: 已拒绝
 */
@Getter
public enum GroupJoinStatus {
    PENDING(0, "待审批"),
    APPROVED(1, "已加入"),
    REJECTED(2, "已拒绝");

    private final int code;
    private final String description;

    GroupJoinStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

}

package com.example.servicequiznest.enums;

/**
 * 小组类型枚举
 * FREE: 公开小组，任何人都可以加入
 * APPROVAL: 需要审批的小组，用户申请加入后需要管理员审批
 * INVITE: 邀请制小组，只有被邀请的用户才能加入
 */
public enum GroupType {
    FREE,
    APPROVAL,
    INVITE
}

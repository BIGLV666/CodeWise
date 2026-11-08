package com.example.servicequiznest.dto;

import com.example.servicequiznest.enums.GroupMemberRole;
import lombok.Data;

/**
 * 设置/取消小组成员管理员请求体。
 */
@Data
public class UpdateMemberRoleDto {

    /** 目标角色：ADMIN 设为管理员，MEMBER 取消管理员；不接受 ROOT。 */
    private GroupMemberRole role;
}

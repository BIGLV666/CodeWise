package com.example.servicequiznest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("group_member")
public class GroupMember {
    @TableId(type = IdType.AUTO)
    private Long groupMemberId;
    private Long userId;
    private Long groupId;
    private GroupMemberRole role;
    /**
     * 小组加入状态
     * PENDING: 待审批
     * APPROVED: 已加入
     * REJECTED: 已拒绝
     * 状态流程：
     * 1. 用户申请加入小组，状态为PENDING
     * 2. 管理员审批通过，状态为APPROVED
     * 3. 管理员审批拒绝，状态为REJECTED
     * 申请先PENDING占位防重复申请
     */
    private GroupJoinStatus joinStatus;


    private LocalDateTime joinTime;
    /**
     * root用户即创建分组
     * 当且仅当role==root时才返回完整记录
     */
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public GroupMember() {
    }

    public GroupMember (Long userId,GroupMemberRole role,Long groupId){
        this.userId=userId;
        this.groupId=groupId;
        creatGroupMember(role);
        this.createTime=LocalDateTime.now();
    }

    /**
     * 根据role动态创建
     */
    private void creatGroupMember(GroupMemberRole role){
        if(role.equals(GroupMemberRole.MEMBER)){
            this.joinStatus=GroupJoinStatus.PENDING;
            this.role=role;
        }
        if(role.equals(GroupMemberRole.ROOT)){
            this.joinStatus=GroupJoinStatus.APPROVED;
            this.joinTime=LocalDateTime.now();
            this.role=role;
        }
    }



}

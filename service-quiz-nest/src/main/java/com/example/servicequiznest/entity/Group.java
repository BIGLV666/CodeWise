package com.example.servicequiznest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.enums.GroupType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`group`")
public class Group {
    @TableId(type = IdType.AUTO)
    private Long groupId;
    //小组名不可为空
    private  String groupName;
    //（root用户id）
    private Long userId;
    //小组描述，可空
    private String groupDescription;
    //小组类型（公开、审批、邀请）
    private GroupType groupType;
    //成员数：APPROVED 成员数（含 root），创建人自己算第一个成员，因此建组时为 1
    private Long memberCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;


    public Group(CreateGroupDto createGroupDto){
        this.groupDescription= createGroupDto.getGroupDescription();
        this.groupName= createGroupDto.getGroupName();
        this.groupType = com.example.servicequiznest.enums.GroupType.valueOf(createGroupDto.getGroupType());
        this.createTime=LocalDateTime.now();
        // 创建人自己就是第一个 APPROVED 成员（member_count 口径 = APPROVED 成员数，含 root）
        this.memberCount=1L;
    }
    public Group(){}
    public Group(GroupDto dto){
        this.groupId=dto.getGroupId();
        this.groupName=dto.getGroupName();
        this.groupType=GroupType.valueOf(dto.getGroupType());
        this.groupDescription=dto.getGroupDescription();
        this.updateTime=LocalDateTime.now();
    }
}

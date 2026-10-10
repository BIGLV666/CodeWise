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
    //成员数
    private Long memberCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;


    public Group(CreateGroupDto createGroupDto){
        this.groupDescription= createGroupDto.getGroupDescription();
        this.groupName= createGroupDto.getGroupName();
        this.groupType = com.example.servicequiznest.enums.GroupType.valueOf(createGroupDto.getGroupType());
        this.createTime=LocalDateTime.now();
        this.memberCount=0L;
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

package com.example.servicequiznest.dto;

import com.example.servicequiznest.enums.GroupType;
import lombok.Data;

/**
 * 修改用全量dto,不包含token
 */
@Data
public class GroupDto {
    /**
     * 不可为空
     */
    private String groupName;
    /**
     * 小组描述，可空
     */
    private String groupDescription;
    /**
     * 小组类型，默认需要审批加入
     */
    private String groupType;
    /**
     * 小组id
     */
    private Long groupId;
}

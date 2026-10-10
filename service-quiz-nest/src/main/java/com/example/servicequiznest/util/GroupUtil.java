package com.example.servicequiznest.util;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.enums.GroupType;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;

import java.util.Set;

public class GroupUtil {

    private static final Set<String> TYPE=Set.of(
            GroupType.FREE.name(),
            GroupType.APPROVAL.name(),
            GroupType.INVITE.name()
    );

    /**
     * 校验dto是否合法，不合法抛出异常
     * @throws BizException 自定义异常
     */
    public static void checkGroup(CreateGroupDto GroupDto){
        if(GroupDto == null){
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if(GroupDto.getGroupName()==null|| GroupDto.getGroupName().isBlank()){
            throw new BizException(QuizNestErrorCode.GROUP_NAME_REQUIRED);
        }
        if(GroupDto.getToken()==null|| GroupDto.getToken().isBlank()){
            throw new BizException(QuizNestErrorCode.CREATE_TOKEN_REQUIRED);
        }
        if(GroupDto.getGroupType()==null|| GroupDto.getGroupType().isBlank()){
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_REQUIRED);
        }
        if(!TYPE.contains(GroupDto.getGroupType())){
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_INVALID);
        }
    }
    public static void checkGroup(GroupDto GroupDto){
        if(GroupDto == null){
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if(GroupDto.getGroupId()==null){
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        if(GroupDto.getGroupName()==null|| GroupDto.getGroupName().isBlank()){
            throw new BizException(QuizNestErrorCode.GROUP_NAME_REQUIRED);
        }
        if(GroupDto.getGroupType()==null|| GroupDto.getGroupType().isBlank()){
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_REQUIRED);
        }
        if(!TYPE.contains(GroupDto.getGroupType())){
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_INVALID);
        }
    }





}

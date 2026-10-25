package com.example.servicequiznest.util.check;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.enums.GroupType;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;

import java.util.Set;

/**
 * 小组入参校验：建组与改组共用同一套加入方式白名单。
 *
 * <p>建组是写操作且会占用一次性创建凭证，因此所有字段必须在写库、占位 Redis 之前校验完，
 * 非法请求不产生任何 IO；修改接口是全量覆盖语义，小组 id 与名称、加入方式都必须显式提交。</p>
 */
public final class CheckGroup {

    /** 当前支持的加入方式；新增枚举值时必须同步登记，否则前端会收到「加入方式不正确」。 */
    private static final Set<String> ALLOWED_GROUP_TYPES = Set.of(
            GroupType.FREE.name(),
            GroupType.APPROVAL.name(),
            GroupType.INVITE.name()
    );

    private CheckGroup() {
    }

    /**
     * 校验建组入参。
     *
     * @param createGroupDto 建组请求体
     * @throws BizException 入参为空、必填项缺失或加入方式非法时抛出
     */
    public static void checkCreate(CreateGroupDto createGroupDto) {
        if (createGroupDto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        checkNameAndType(createGroupDto.getGroupName(), createGroupDto.getGroupType());
        if (createGroupDto.getToken() == null || createGroupDto.getToken().isBlank()) {
            throw new BizException(QuizNestErrorCode.CREATE_TOKEN_REQUIRED);
        }
    }

    /**
     * 校验修改小组入参。
     *
     * @param groupDto 修改请求体
     * @throws BizException 入参为空、小组 id 缺失或字段非法时抛出
     */
    public static void checkUpdate(GroupDto groupDto) {
        if (groupDto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if (groupDto.getGroupId() == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        checkNameAndType(groupDto.getGroupName(), groupDto.getGroupType());
    }

    /** 建组与改组共用的名称、加入方式校验。 */
    private static void checkNameAndType(String groupName, String groupType) {
        if (groupName == null || groupName.isBlank()) {
            throw new BizException(QuizNestErrorCode.GROUP_NAME_REQUIRED);
        }
        if (groupType == null || groupType.isBlank()) {
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_REQUIRED);
        }
        if (!ALLOWED_GROUP_TYPES.contains(groupType)) {
            throw new BizException(QuizNestErrorCode.GROUP_TYPE_INVALID);
        }
    }
}

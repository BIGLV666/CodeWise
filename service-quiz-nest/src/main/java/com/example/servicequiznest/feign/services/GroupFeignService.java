package com.example.servicequiznest.feign.services;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.enums.UserRoleType;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 小组内部权限判断：供 service-question 通过 Feign 查询当前用户对小组题目的可见性/维护权限。
 *
 * <p>题目本身存放在 service-question，小组只保存题目索引，因此题目服务看不到
 * 「这道题属于哪个小组、当前用户是不是组员」，必须回调本服务判断。</p>
 *
 * <p>平台管理员（{@link UserRoleType#ROLE_ROOT} / {@link UserRoleType#ROLE_ADMIN}）保留
 * 全局可见能力；普通用户只能看自己已加入小组的题目，避免把小组私密题扩散出去。</p>
 */
@Slf4j
@Service
public class GroupFeignService {
    private final UserFeignClient userFeignClient;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupQuestionMapper groupQuestionMapper;

    public GroupFeignService(
            UserFeignClient userFeignClient,
            GroupMemberMapper groupMemberMapper,
            GroupQuestionMapper groupQuestionMapper
    ){
        this.userFeignClient = userFeignClient;
        this.groupMemberMapper = groupMemberMapper;
        this.groupQuestionMapper = groupQuestionMapper;
    }

    /**
     * 用于题目服务校验该人是否为小组成员，即是否有权限读取该题目。
     *
     * <p>只有私密题会走到这里（公开题由题目服务直接放行）。平台题一旦被某个小组
     * 引用，同样要求用户属于该小组——不能因为 {@code is_plat_from_question=1}
     * 就绕过成员校验，否则创建者把自己 status=3 的私密题加入小组后，题目会对
     * 所有登录用户可见。</p>
     *
     * <p>同一道平台题可能被多个小组引用，因此按索引列表判断：用户只要属于其中
     * 任意一个 APPROVED 小组即通过。</p>
     *
     * @param questionId 题目 id；要求组内 APPROVED 成员或平台管理员
     *                   （{@link UserRoleType#ROLE_ROOT} / {@link UserRoleType#ROLE_ADMIN}）
     * @return true 表示有权限；无权限时抛出 {@link BizException}，由调用方透传给用户
     */
    public boolean checkIsGroupMember(Long questionId){
        List<GroupQuestion> mappings = requireGroupQuestionMappings(questionId);
        Long userId = requireLogin();
        List<Long> groupIds = mappings.stream()
                .map(GroupQuestion::getGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!groupIds.isEmpty()) {
            Long approved = groupMemberMapper.selectCount(new QueryWrapper<GroupMember>()
                    .eq("user_id", userId)
                    .in("group_id", groupIds)
                    .eq("join_status", GroupJoinStatus.APPROVED.name()));
            if (approved != null && approved > 0) {
                return true;
            }
        }
        // 不是组员时，平台管理员仍可查看小组私密题
        if (isPlatformAdmin(userId)) {
            return true;
        }
        throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
    }

    /**
     * 判断该用户是否有权限添加/修改题目样例。
     *
     * @param questionId 题目 id
     * @return 平台题目返回 false（不允许在小组内改）；小组自建题要求组内 APPROVED 的 ROOT/ADMIN
     * @throws BizException 题目不存在、未登录或无权限时抛出
     */
    public boolean checkIsGroupAdmin(Long questionId){
        List<GroupQuestion> mappings = requireGroupQuestionMappings(questionId);
        // 平台题目不允许个人修改，管理员也不可修改组内平台题样例
        if (mappings.stream()
                .anyMatch(mapping -> Integer.valueOf(1).equals(mapping.getIsPlatFromQuestion()))) {
            return false;
        }
        // 自建题只会属于一个小组，取第一条索引即可
        checkGroupAdminRole(requireLogin(), mappings.getFirst().getGroupId());
        return true;
    }

    /**
     * 判断题目是否属于该小组，且当前用户是否为该组 APPROVED 成员。
     *
     * <p>小组提交与普通浏览不同：公开题也必须校验小组归属，否则任意登录用户
     * 都能把提交记录写进任意小组。</p>
     *
     * @param questionId 题目 id
     * @param groupId    小组 id
     * @return true 表示校验通过
     * @throws BizException 参数非法、未登录、非组员或题目不在组内
     */
    public boolean checkQuestionInGroupAndIsGroupMember(Long questionId, Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BizException(QuizNestErrorCode.INVALID_GROUP_ID);
        }
        if (questionId == null || questionId <= 0) {
            throw new BizException(QuizNestErrorCode.INVALID_QUESTION_ID);
        }
        Long userId = requireLogin();
        // 先校验成员身份，再查题目索引：非组员无法通过错误码探测题目是否存在
        requireApprovedMember(userId, groupId);
        Long count = groupQuestionMapper.selectCount(new QueryWrapper<GroupQuestion>()
                .eq("group_id", groupId)
                .eq("question_id", questionId));
        if (count == null || count == 0) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }
        return true;
    }

    /**
     * 小组级只读权限：当前用户必须是该组 APPROVED 成员。
     *
     * <p>用于小组提交记录列表等「不依赖某道题目」的组内只读接口。</p>
     *
     * @param groupId 小组 id
     * @return true 表示校验通过
     * @throws BizException 参数非法、未登录或非组员
     */
    public boolean checkIsGroupMemberByGroupId(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new BizException(QuizNestErrorCode.INVALID_GROUP_ID);
        }
        requireApprovedMember(requireLogin(), groupId);
        return true;
    }

    /**
     * 浏览鉴权：只要求「已加入」（APPROVED），不要求管理员。
     *
     * <p>不能复用 {@link #checkGroupAdminRole(Long, Long)}——那是添加题目的管理员校验，
     * 会把普通成员挡在外面；同时必须校验 {@code join_status}，
     * 否则 PENDING/REJECTED 占位记录也能看到小组题库。</p>
     *
     * @return 已通过 APPROVED 校验的成员记录
     */
    private GroupMember requireApprovedMember(Long userId, Long groupId) {
        GroupMember groupMember = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", userId)
                .eq("group_id", groupId));
        if (groupMember == null || !GroupJoinStatus.APPROVED.equals(groupMember.getJoinStatus())) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }
        return groupMember;
    }

    /**
     * 按题目 id 取出全部小组索引。
     *
     * <p>平台题可以被多个小组引用，因此不能用 selectOne；权限判断按索引列表进行。</p>
     */
    private List<GroupQuestion> requireGroupQuestionMappings(Long questionId){
        if(questionId==null||questionId<=0){
            throw new BizException(QuizNestErrorCode.INVALID_QUESTION_ID);
        }
        List<GroupQuestion> mappings = groupQuestionMapper.selectList(
                new QueryWrapper<GroupQuestion>().eq("question_id", questionId)
        );
        if(mappings==null||mappings.isEmpty()){
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }
        return mappings;
    }

    /** 内部调用必须带用户身份；没有身份时直接拒绝，避免用 null 去查用户服务。 */
    private Long requireLogin(){
        Long userId = UserContext.getUserId();
        if(userId==null){
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        return userId;
    }

    /** 平台管理员判定：用户服务不可用时按下游异常失败，不把用户误判成普通成员。 */
    private boolean isPlatformAdmin(Long userId){
        Result<UserDto> body;
        try {
            body = userFeignClient.getUserInfo(userId);
        } catch (Exception e) {
            log.error("查询用户角色失败，userId={}", userId, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if(body==null||!Integer.valueOf(200).equals(body.getCode())||body.getData()==null){
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        Integer roleId = body.getData().getRoleId();
        // 只认 root(0)/管理员(2)；roleId 缺失时按普通用户处理，不能因 null 被误判为管理员
        return Integer.valueOf(UserRoleType.ROLE_ROOT.getValue()).equals(roleId)
                || Integer.valueOf(UserRoleType.ROLE_ADMIN.getValue()).equals(roleId);
    }

    /**
     * 判断该成员是否属于小组且是否为管理员及超级管理。
     *
     * <p>角色判断必须在 APPROVED 之后：PENDING/REJECTED 占位记录即使 role 是 ADMIN
     * 也不能维护题目。</p>
     *
     * @throws BizException 抛出自定义异常，无须捕获，透穿给用户即可
     */
    private void checkGroupAdminRole(Long userId, Long groupId) {
        GroupMember groupMember = requireApprovedMember(userId, groupId);
        if (!GroupMemberRole.ROOT.equals(groupMember.getRole())
                && !GroupMemberRole.ADMIN.equals(groupMember.getRole())) {
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_ADD_QUESTION);
        }
    }
}

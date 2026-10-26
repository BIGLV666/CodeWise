package com.example.servicequiznest.feign.services;

import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组权限判断：平台题、普通组员、管理员、待审批占位记录与下游故障的边界。
 */
@ExtendWith(MockitoExtension.class)
class GroupFeignServiceTest {

    private static final long GROUP_ID = 88L;
    private static final long QUESTION_ID = 7L;
    private static final long USER_ID = 9L;

    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private GroupMemberMapper groupMemberMapper;
    @Mock
    private GroupQuestionMapper groupQuestionMapper;

    private GroupFeignService groupFeignService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        groupFeignService = new GroupFeignService(
                userFeignClient, groupMemberMapper, groupQuestionMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("题目 id 非法：直接拒绝，不查索引")
    void invalidQuestionIdRejected() {
        assertCode(QuizNestErrorCode.INVALID_QUESTION_ID, () -> groupFeignService.checkIsGroupMember(null));
        assertCode(QuizNestErrorCode.INVALID_QUESTION_ID, () -> groupFeignService.checkIsGroupMember(0L));
        assertCode(QuizNestErrorCode.INVALID_QUESTION_ID, () -> groupFeignService.checkIsGroupAdmin(-1L));
        verifyNoInteractions(groupQuestionMapper, groupMemberMapper, userFeignClient);
    }

    @Test
    @DisplayName("题目不在任何小组：返回未找到")
    void questionNotInGroupRejected() {
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of());

        assertCode(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
        verifyNoInteractions(groupMemberMapper, userFeignClient);
    }

    @Test
    @DisplayName("平台题不再自动放行：未加入小组的普通用户仍被拒绝")
    void platformQuestionStillRequiresMembership() {
        stubQuestion(1);
        stubGroupMemberCount(0L);
        stubUserRole(1);

        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
    }

    @Test
    @DisplayName("平台题：维护权限恒为 false，不查成员")
    void platformQuestionAdminDenied() {
        stubQuestion(1);

        assertThat(groupFeignService.checkIsGroupAdmin(QUESTION_ID)).isFalse();
        verifyNoInteractions(groupMemberMapper, userFeignClient);
    }

    @Test
    @DisplayName("已加入的普通成员：可读取自建题，不查用户角色")
    void approvedMemberCanRead() {
        stubQuestion(0);
        stubGroupMemberCount(1L);

        assertThat(groupFeignService.checkIsGroupMember(QUESTION_ID)).isTrue();
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("平台题被小组引用后：组内 APPROVED 成员仍可读取")
    void approvedMemberCanReadPlatformQuestion() {
        stubQuestion(1);
        stubGroupMemberCount(1L);

        assertThat(groupFeignService.checkIsGroupMember(QUESTION_ID)).isTrue();
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("未加入的普通用户：拒绝读取")
    void nonMemberNormalUserDenied() {
        stubQuestion(0);
        stubGroupMemberCount(0L);
        stubUserRole(1);

        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
    }

    @Test
    @DisplayName("未加入的平台管理员：允许读取")
    void nonMemberAdminCanRead() {
        stubQuestion(0);
        stubGroupMemberCount(0L);
        stubUserRole(2);

        assertThat(groupFeignService.checkIsGroupMember(QUESTION_ID)).isTrue();
    }

    @Test
    @DisplayName("待审批成员即使 role=ADMIN：也不能读取或维护")
    void pendingMemberDenied() {
        stubQuestion(0);
        // 成员校验走 COUNT（0 条 APPROVED），管理员校验走 selectOne（PENDING 记录）
        stubGroupMemberCount(0L);
        stubAdminMember(GroupMemberRole.ADMIN, GroupJoinStatus.PENDING);
        stubUserRole(1);

        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupAdmin(QUESTION_ID));
    }

    @Test
    @DisplayName("用户服务不可用：按下游异常失败，不把用户误判为普通成员")
    void userServiceUnavailableDenied() {
        stubQuestion(0);
        stubGroupMemberCount(0L);
        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(null);

        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));

        when(userFeignClient.getUserInfo(USER_ID)).thenThrow(new RuntimeException("timeout"));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
    }

    @Test
    @DisplayName("维护权限：ROOT/ADMIN 通过，MEMBER 拒绝")
    void adminCheckByRole() {
        stubQuestion(0);

        stubAdminMember(GroupMemberRole.ROOT, GroupJoinStatus.APPROVED);
        assertThat(groupFeignService.checkIsGroupAdmin(QUESTION_ID)).isTrue();

        stubAdminMember(GroupMemberRole.ADMIN, GroupJoinStatus.APPROVED);
        assertThat(groupFeignService.checkIsGroupAdmin(QUESTION_ID)).isTrue();

        stubAdminMember(GroupMemberRole.MEMBER, GroupJoinStatus.APPROVED);
        assertCode(QuizNestErrorCode.NO_PERMISSION_ADD_QUESTION,
                () -> groupFeignService.checkIsGroupAdmin(QUESTION_ID));
    }

    @Test
    @DisplayName("同一平台题被多个小组引用：属于任一小组即可读取")
    void memberOfAnyReferencingGroupCanRead() {
        GroupQuestion first = question(1);
        GroupQuestion second = GroupQuestion.builder()
                .groupQuestionId(101L)
                .groupId(GROUP_ID + 1)
                .questionId(QUESTION_ID)
                .isPlatFromQuestion(1)
                .build();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(first, second));
        stubGroupMemberCount(1L);

        assertThat(groupFeignService.checkIsGroupMember(QUESTION_ID)).isTrue();
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("无登录身份：自建题的成员与管理员校验都拒绝，不查成员")
    void loginRequired() {
        UserContext.clear();
        stubQuestion(0);

        assertCode(CommonErrorCode.LOGIN_REQUIRED, () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
        assertCode(CommonErrorCode.LOGIN_REQUIRED, () -> groupFeignService.checkIsGroupAdmin(QUESTION_ID));
        verifyNoInteractions(groupMemberMapper, userFeignClient);
    }

    @Test
    @DisplayName("角色缺失（roleId=null）：按普通用户处理，不能误判为管理员")
    void nullRoleIsNotAdmin() {
        stubQuestion(0);
        stubGroupMemberCount(0L);
        stubUserRole(null);

        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMember(QUESTION_ID));
    }

    @Test
    @DisplayName("小组提交：APPROVED 成员且题目在组内 → 通过")
    void groupSubmitAllowedForApprovedMember() {
        when(groupMemberMapper.selectOne(any()))
                .thenReturn(member(GroupMemberRole.MEMBER, GroupJoinStatus.APPROVED));
        when(groupQuestionMapper.selectCount(any())).thenReturn(1L);

        assertThat(groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID))
                .isTrue();
    }

    @Test
    @DisplayName("小组提交：非组员/待审批成员拒绝，且不查题目索引")
    void groupSubmitRejectsNonApprovedMember() {
        when(groupMemberMapper.selectOne(any())).thenReturn(null);
        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID));

        when(groupMemberMapper.selectOne(any()))
                .thenReturn(member(GroupMemberRole.MEMBER, GroupJoinStatus.PENDING));
        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID));

        verifyNoInteractions(groupQuestionMapper);
    }

    @Test
    @DisplayName("小组提交：题目不在该小组 → 明确「未找到该题目」")
    void groupSubmitRejectsQuestionNotInGroup() {
        when(groupMemberMapper.selectOne(any()))
                .thenReturn(member(GroupMemberRole.MEMBER, GroupJoinStatus.APPROVED));
        when(groupQuestionMapper.selectCount(any())).thenReturn(0L);

        assertCode(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID));
    }

    @Test
    @DisplayName("小组提交：非法 id 直接拒绝，不查库")
    void groupSubmitRejectsInvalidIds() {
        assertCode(QuizNestErrorCode.INVALID_GROUP_ID,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, null));
        assertCode(QuizNestErrorCode.INVALID_GROUP_ID,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, 0L));
        assertCode(QuizNestErrorCode.INVALID_QUESTION_ID,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(null, GROUP_ID));
        assertCode(QuizNestErrorCode.INVALID_QUESTION_ID,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(0L, GROUP_ID));

        verifyNoInteractions(groupMemberMapper, groupQuestionMapper);
    }

    @Test
    @DisplayName("小组提交：未登录直接拒绝，不查库")
    void groupSubmitRequiresLogin() {
        UserContext.clear();

        assertCode(CommonErrorCode.LOGIN_REQUIRED,
                () -> groupFeignService.checkQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID));
        verifyNoInteractions(groupMemberMapper, groupQuestionMapper);
    }

    @Test
    @DisplayName("小组级成员校验：APPROVED 成员通过")
    void groupMemberByGroupIdAllowed() {
        when(groupMemberMapper.selectOne(any()))
                .thenReturn(member(GroupMemberRole.MEMBER, GroupJoinStatus.APPROVED));

        assertThat(groupFeignService.checkIsGroupMemberByGroupId(GROUP_ID)).isTrue();
        verifyNoInteractions(groupQuestionMapper);
    }

    @Test
    @DisplayName("小组级成员校验：非组员/待审批成员拒绝")
    void groupMemberByGroupIdRejectsNonApproved() {
        when(groupMemberMapper.selectOne(any())).thenReturn(null);
        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMemberByGroupId(GROUP_ID));

        when(groupMemberMapper.selectOne(any()))
                .thenReturn(member(GroupMemberRole.MEMBER, GroupJoinStatus.PENDING));
        assertCode(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP,
                () -> groupFeignService.checkIsGroupMemberByGroupId(GROUP_ID));
        verifyNoInteractions(groupQuestionMapper);
    }

    @Test
    @DisplayName("小组级成员校验：非法 groupId / 未登录直接拒绝，不查库")
    void groupMemberByGroupIdParamAndLoginChecks() {
        assertCode(QuizNestErrorCode.INVALID_GROUP_ID,
                () -> groupFeignService.checkIsGroupMemberByGroupId(null));
        assertCode(QuizNestErrorCode.INVALID_GROUP_ID,
                () -> groupFeignService.checkIsGroupMemberByGroupId(0L));

        UserContext.clear();
        assertCode(CommonErrorCode.LOGIN_REQUIRED,
                () -> groupFeignService.checkIsGroupMemberByGroupId(GROUP_ID));
        verifyNoInteractions(groupMemberMapper, groupQuestionMapper);
    }

    private void stubQuestion(int isPlatform) {
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(question(isPlatform)));
    }

    private void stubGroupMemberCount(long count) {
        when(groupMemberMapper.selectCount(any())).thenReturn(count);
    }

    private void stubAdminMember(GroupMemberRole role, GroupJoinStatus status) {
        when(groupMemberMapper.selectOne(any())).thenReturn(member(role, status));
    }

    private static GroupMember member(GroupMemberRole role, GroupJoinStatus status) {
        GroupMember member = new GroupMember();
        member.setRole(role);
        member.setJoinStatus(status);
        return member;
    }

    private void stubUserRole(Integer roleId) {
        UserDto user = new UserDto();
        user.setRoleId(roleId);
        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(Result.success(user));
    }

    private static GroupQuestion question(int isPlatform) {
        return GroupQuestion.builder()
                .groupQuestionId(100L)
                .groupId(GROUP_ID)
                .questionId(QUESTION_ID)
                .isPlatFromQuestion(isPlatform)
                .build();
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

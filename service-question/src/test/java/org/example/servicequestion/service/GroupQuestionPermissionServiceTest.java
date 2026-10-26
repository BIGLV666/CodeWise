package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import org.example.serviceapi.feign.GroupFeignClient;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 私密题可见性：创建者、组员、平台管理员三条放行路径，以及 quiz-nest 拒绝码与
 * 真正下游故障的区分。
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionPermissionServiceTest {

    private static final long QUESTION_ID = 7L;
    private static final long OWNER_ID = 10L;
    private static final long USER_ID = 20L;
    private static final String INTERNAL_TOKEN = "test-internal-token";

    @Mock
    private GroupFeignClient groupFeignClient;
    @Mock
    private UserFeignClient userFeignClient;

    private GroupQuestionPermissionService permissionService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        permissionService = new GroupQuestionPermissionService(
                groupFeignClient, userFeignClient, INTERNAL_TOKEN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建者：直接放行，不调用小组/用户服务")
    void creatorAllowedWithoutRemoteCalls() {
        Question question = question(OWNER_ID);
        UserContext.setUserId(OWNER_ID);

        assertThat(permissionService.canAccessPrivateQuestion(question)).isTrue();
        verifyNoInteractions(groupFeignClient, userFeignClient);
    }

    @Test
    @DisplayName("组内 APPROVED 成员：放行")
    void groupMemberAllowed() {
        stubGroupMember(true);

        assertThat(permissionService.canAccessPrivateQuestion(question(OWNER_ID))).isTrue();
        verify(groupFeignClient).checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID);
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("明确不是组员：普通拒绝，不再查用户角色")
    void nonMemberDeniedWithoutUserLookup() {
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.error("不是组员", 4041101));

        assertThat(permissionService.canAccessPrivateQuestion(question(OWNER_ID))).isFalse();
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("题目不在任何小组 + 平台管理员：沿用历史语义放行")
    void adminAllowedForNonGroupPrivateQuestion() {
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.error("不在小组", 4041104));
        stubUserRole(2);

        assertThat(permissionService.canAccessPrivateQuestion(question(OWNER_ID))).isTrue();
    }

    @Test
    @DisplayName("题目不在任何小组 + 普通用户：拒绝")
    void normalUserDeniedForNonGroupPrivateQuestion() {
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.error("不在小组", 4041104));
        stubUserRole(1);

        assertThat(permissionService.canAccessPrivateQuestion(question(OWNER_ID))).isFalse();
    }

    @Test
    @DisplayName("未登录：直接拒绝，不调用下游")
    void anonymousRejected() {
        UserContext.clear();

        assertCode(CommonErrorCode.LOGIN_REQUIRED,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
        verifyNoInteractions(groupFeignClient, userFeignClient);
    }

    @Test
    @DisplayName("quiz-nest 返回 null/未知错误码/null code/空 data：按下游不可用失败")
    void malformedGroupResponseMappedToDownstream() {
        Result<Boolean> nullCode = Result.success(true);
        nullCode.setCode(null);
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(null)
                .thenReturn(Result.error("内部错误", 5001101))
                .thenReturn(nullCode)
                .thenReturn(Result.success((Boolean) null));

        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
        verifyNoInteractions(userFeignClient);
    }

    @Test
    @DisplayName("Feign 抛异常：保留 cause，按下游不可用失败")
    void feignFailureMappedToDownstream() {
        RuntimeException cause = new RuntimeException("connect timeout");
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID)).thenThrow(cause);

        assertThatThrownBy(() -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
                    assertThat(e.getCause()).isSameAs(cause);
                });
    }

    @Test
    @DisplayName("管理员校验：用户服务失败时失败关闭，数据缺失返回用户不存在")
    void userLookupFailures() {
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.error("不在小组", 4041104));
        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(null);
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));

        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(Result.success((UserDto) null));
        assertCode(QuestionErrorCode.USER_NOT_FOUND,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));

        when(userFeignClient.getUserInfo(USER_ID)).thenThrow(new RuntimeException("timeout"));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canAccessPrivateQuestion(question(OWNER_ID)));
    }

    @Test
    @DisplayName("角色 root(0) 也按管理员放行")
    void rootAllowedForNonGroupPrivateQuestion() {
        when(groupFeignClient.checkIsGroupMember(eq(INTERNAL_TOKEN), anyLong()))
                .thenReturn(Result.error("不在小组", 4041104));
        stubUserRole(0);

        assertThat(permissionService.canAccessPrivateQuestion(question(OWNER_ID))).isTrue();
    }

    @Test
    @DisplayName("维护样例：私密题看小组 ROOT/ADMIN，非私密题看平台管理员")
    void maintainSamplesPermission() {
        when(groupFeignClient.checkIsGroupAdmin(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.success(true));
        assertThat(permissionService.canMaintainQuestionSamples(question(OWNER_ID))).isTrue();

        when(groupFeignClient.checkIsGroupAdmin(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.success(false));
        assertThat(permissionService.canMaintainQuestionSamples(question(OWNER_ID))).isFalse();

        when(groupFeignClient.checkIsGroupAdmin(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.error("无权限", 4031103));
        assertThat(permissionService.canMaintainQuestionSamples(question(OWNER_ID))).isFalse();

        Question publicQuestion = question(OWNER_ID);
        publicQuestion.setStatus(1);
        stubUserRole(2);
        assertThat(permissionService.canMaintainQuestionSamples(publicQuestion)).isTrue();

        stubUserRole(1);
        assertThat(permissionService.canMaintainQuestionSamples(publicQuestion)).isFalse();
    }

    @Test
    @DisplayName("维护样例：quiz-nest 异常按下游不可用失败，不误报无权限")
    void maintainSamplesDownstreamFailure() {
        when(groupFeignClient.checkIsGroupAdmin(INTERNAL_TOKEN, QUESTION_ID))
                .thenThrow(new RuntimeException("timeout"));

        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.canMaintainQuestionSamples(question(OWNER_ID)));
    }

    @Test
    @DisplayName("小组提交：题目在组内且用户是 APPROVED 成员时放行")
    void groupSubmitAllowedForApprovedMember() {
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenReturn(Result.success(true));

        permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L);

        verify(groupFeignClient)
                .checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L);
    }

    @Test
    @DisplayName("小组提交：非组员返回明确权限错误，不再混用 404/资源受限")
    void groupSubmitRejectsNonMember() {
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenReturn(Result.error("不是组员", 4041101));

        assertCode(QuestionErrorCode.GROUP_MEMBER_REQUIRED,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
    }

    @Test
    @DisplayName("小组提交：题目不在小组返回明确「不在题库」错误")
    void groupSubmitRejectsQuestionNotInGroup() {
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenReturn(Result.error("题目不在小组", 4041104));

        assertCode(QuestionErrorCode.GROUP_QUESTION_NOT_IN_GROUP,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
    }

    @Test
    @DisplayName("小组提交：下游 data=false 或未知错误码不能误放行")
    void groupSubmitRejectsFalseDataAndUnknownCode() {
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenReturn(Result.success(false))
                .thenReturn(Result.error("未知错误", 5001101));

        assertCode(QuestionErrorCode.GROUP_MEMBER_REQUIRED,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
    }

    @Test
    @DisplayName("小组提交：null code 不得拆箱 NPE，按下游不可用失败")
    void groupSubmitNullCodeMappedToDownstream() {
        Result<Boolean> nullCode = Result.success(true);
        nullCode.setCode(null);
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenReturn(null)
                .thenReturn(nullCode);

        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
    }

    @Test
    @DisplayName("小组提交：Feign 异常保留 cause，按下游不可用失败")
    void groupSubmitFeignFailureMappedToDownstream() {
        RuntimeException cause = new RuntimeException("connect timeout");
        when(groupFeignClient.checkQuestionInGroupAndIsGroupMember(INTERNAL_TOKEN, QUESTION_ID, 88L))
                .thenThrow(cause);

        assertThatThrownBy(() -> permissionService
                .assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
                    assertThat(e.getCause()).isSameAs(cause);
                });
    }

    @Test
    @DisplayName("小组提交：参数非法或未登录时不调用下游")
    void groupSubmitParamAndLoginChecks() {
        assertCode(QuestionErrorCode.QUESTION_ID_REQUIRED,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(null, 88L));
        assertCode(QuestionErrorCode.GROUP_ID_REQUIRED,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, null));
        assertCode(QuestionErrorCode.GROUP_ID_INVALID,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 0L));

        UserContext.clear();
        assertCode(CommonErrorCode.LOGIN_REQUIRED,
                () -> permissionService.assertQuestionInGroupAndIsGroupMember(QUESTION_ID, 88L));
        verifyNoInteractions(groupFeignClient, userFeignClient);
    }

    @Test
    @DisplayName("小组级只读：APPROVED 成员放行")
    void approvedGroupMemberAllowed() {
        when(groupFeignClient.checkIsGroupMemberByGroupId(INTERNAL_TOKEN, 88L))
                .thenReturn(Result.success(true));

        permissionService.assertApprovedGroupMember(88L);

        verify(groupFeignClient).checkIsGroupMemberByGroupId(INTERNAL_TOKEN, 88L);
    }

    @Test
    @DisplayName("小组级只读：非组员/data=false 返回查看权限错误")
    void nonMemberCannotViewGroupRecords() {
        when(groupFeignClient.checkIsGroupMemberByGroupId(INTERNAL_TOKEN, 88L))
                .thenReturn(Result.error("不是组员", 4041101))
                .thenReturn(Result.success(false));

        assertCode(QuestionErrorCode.GROUP_SUBMIT_RECORD_VIEW_FORBIDDEN,
                () -> permissionService.assertApprovedGroupMember(88L));
        assertCode(QuestionErrorCode.GROUP_SUBMIT_RECORD_VIEW_FORBIDDEN,
                () -> permissionService.assertApprovedGroupMember(88L));
    }

    @Test
    @DisplayName("小组级只读：null code/未知码按下游不可用失败，不拆箱 NPE")
    void groupMemberMalformedResponseMappedToDownstream() {
        Result<Boolean> nullCode = Result.success(true);
        nullCode.setCode(null);
        when(groupFeignClient.checkIsGroupMemberByGroupId(INTERNAL_TOKEN, 88L))
                .thenReturn(null)
                .thenReturn(nullCode)
                .thenReturn(Result.error("未知错误", 5001101));

        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertApprovedGroupMember(88L));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertApprovedGroupMember(88L));
        assertCode(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> permissionService.assertApprovedGroupMember(88L));
    }

    @Test
    @DisplayName("小组级只读：Feign 异常保留 cause；参数/登录前置校验不查下游")
    void groupMemberParamLoginAndFeignFailure() {
        assertCode(QuestionErrorCode.GROUP_ID_REQUIRED,
                () -> permissionService.assertApprovedGroupMember(null));
        assertCode(QuestionErrorCode.GROUP_ID_INVALID,
                () -> permissionService.assertApprovedGroupMember(0L));

        UserContext.clear();
        assertCode(CommonErrorCode.LOGIN_REQUIRED,
                () -> permissionService.assertApprovedGroupMember(88L));
        verifyNoInteractions(groupFeignClient);

        UserContext.setUserId(USER_ID);
        RuntimeException cause = new RuntimeException("timeout");
        when(groupFeignClient.checkIsGroupMemberByGroupId(INTERNAL_TOKEN, 88L)).thenThrow(cause);
        assertThatThrownBy(() -> permissionService.assertApprovedGroupMember(88L))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
                    assertThat(e.getCause()).isSameAs(cause);
                });
    }

    private void stubGroupMember(boolean member) {
        when(groupFeignClient.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.success(member));
    }

    private void stubUserRole(int roleId) {
        UserDto user = new UserDto();
        user.setRoleId(roleId);
        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(Result.success(user));
    }

    private static Question question(long ownerId) {
        return Question.builder()
                .questionId(QUESTION_ID)
                .status(3)
                .createUserId(ownerId)
                .build();
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

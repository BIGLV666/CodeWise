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

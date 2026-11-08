package com.example.servicequiznest.controller;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.dto.JoinGroupDto;
import com.example.servicequiznest.dto.ApproveMemberDto;
import com.example.servicequiznest.dto.SendGroupBroadcastDto;
import com.example.servicequiznest.dto.SendJoinTokenDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.dto.UpdateMemberRoleDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.service.GroupBroadcastService;
import com.example.servicequiznest.service.GroupQuestionService;
import com.example.servicequiznest.service.GroupService;
import com.example.servicequiznest.vo.GroupMembershipVo;
import com.example.servicequiznest.vo.GroupQuestionVo;
import com.example.servicequiznest.vo.MemberVO;
import com.example.servicequiznest.vo.UserGroup;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.CursorPageResult;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.dto.question.QuestionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.example.servicequiznest.vo.MinimalQuestionVo;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GroupController 契约测试：路由方法都委托给 GroupService，且每条对外路由都显式限流。
 *
 * <p>quiz-nest 的 {@code api.governance.rate-limit.default-limit=-1} 表示「只对标注
 * {@code @RateLimit} 的路由生效」，漏标等于该接口完全不限流（邀请码只有 6 位，
 * 这是必须守住的门槛），所以用反射做回归护栏。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock
    private GroupService groupService;

    @Mock
    private GroupQuestionService groupQuestionService;

    @Mock
    private GroupBroadcastService groupBroadcastService;

    @InjectMocks
    private GroupController groupController;

    @Test
    @DisplayName("路由委托：创建凭证/建组/改组/详情/题目列表/退出/加入/发邀请")
    void routesDelegateToService() {
        CreateGroupDto createDto = new CreateGroupDto();
        Group created = new Group();
        created.setGroupId(88L);
        when(groupService.cerateGroup(createDto)).thenReturn(created);
        assertThat(groupController.createGroup(createDto).getData()).isSameAs(created);

        GroupDto updateDto = new GroupDto();
        updateDto.setGroupId(88L);
        when(groupService.updateGroup(updateDto)).thenReturn(created);
        assertThat(groupController.updateGroup(updateDto).getData()).isSameAs(created);

        when(groupService.getGroup(88L)).thenReturn(created);
        assertThat(groupController.getGroup(88L).getData()).isSameAs(created);

        CursorPageResult<MinimalQuestionVo> page = CursorPageResult.<MinimalQuestionVo>builder()
                .records(List.of())
                .hasNext(false)
                .build();
        when(groupQuestionService.cursorQuestion(88L, null, 20)).thenReturn(page);
        assertThat(groupController.cursorQuestions(88L, null, 20).getData()).isSameAs(page);
        verify(groupQuestionService).cursorQuestion(88L, null, 20);

        CreateGroupQuestionDto addQuestionDto = CreateGroupQuestionDto.builder()
                .groupId(999L)
                .questionId(7L)
                .build();
        Result<Void> added = groupController.addQuestion(88L, addQuestionDto);
        assertThat(added.getCode()).isEqualTo(200);
        // 路径 groupId 必须覆盖请求体，防止 body/path 不一致把题加到别的小组
        assertThat(addQuestionDto.getGroupId()).isEqualTo(88L);
        verify(groupQuestionService).addQuestion(addQuestionDto);

        GroupQuestionVo detail = new GroupQuestionVo(QuestionDto.builder().questionId(7L).build(), 100L);
        when(groupQuestionService.getQuestion(100L, 88L)).thenReturn(detail);
        assertThat(groupController.getQuestion(88L, 100L).getData()).isSameAs(detail);

        UpdateGroupQuestionDto updateQuestionDto = UpdateGroupQuestionDto.builder().title("改后标题").build();
        Result<Void> updated = groupController.updateQuestion(88L, 100L, updateQuestionDto);
        assertThat(updated.getCode()).isEqualTo(200);
        verify(groupQuestionService).updateGroupQuestion(updateQuestionDto, 100L, 88L);

        Result<Void> deleted = groupController.deleteQuestion(88L, 100L);
        assertThat(deleted.getCode()).isEqualTo(200);
        verify(groupQuestionService).deleteGroupQuestion(100L, 88L);

        when(groupService.getToken()).thenReturn("create-token");
        assertThat(groupController.getCreateToken().getData()).isEqualTo("create-token");

        when(groupService.leaveGroup(88L, 9L)).thenReturn(true);
        assertThat(groupController.leaveGroup(88L, 9L).getData()).isTrue();

        JoinGroupDto joinDto = new JoinGroupDto();
        joinDto.setToken("012345");
        GroupMembershipVo membership = new GroupMembershipVo(
                88L, 1L, 9L, GroupMemberRole.MEMBER,
                com.example.servicequiznest.enums.GroupJoinStatus.APPROVED, LocalDateTime.now());
        when(groupService.joinGroup(88L, "012345")).thenReturn(membership);
        Result<GroupMembershipVo> joined = groupController.joinGroup(88L, joinDto);
        assertThat(joined.getCode()).isEqualTo(200);
        assertThat(joined.getData()).isSameAs(membership);
        verify(groupService).joinGroup(88L, "012345");
        when(groupService.joinGroup(88L, null)).thenReturn(membership);
        groupController.joinGroup(88L, null);
        verify(groupService).joinGroup(88L, null);

        SendJoinTokenDto sendDto = new SendJoinTokenDto();
        sendDto.setToUserId(9L);
        sendDto.setContent("一起刷题");
        Result<Void> sent = groupController.sendJoinToken(88L, sendDto);
        assertThat(sent.getCode()).isEqualTo(200);
        verify(groupService).sendJoinTokenById(88L, 9L, "一起刷题");

        List<UserGroup> myGroups = List.of(new UserGroup(
                88L, "算法小组", GroupMemberRole.MEMBER, 3L, LocalDateTime.now()));
        when(groupService.getAllUserGroup()).thenReturn(myGroups);
        assertThat(groupController.myGroups().getData()).isSameAs(myGroups);

        List<MemberVO> pending = List.of(new MemberVO(
                88L, 123L, 9L, "avatar", "昵称", GroupMemberRole.MEMBER, null));
        when(groupService.approvedUsers(88L, 0)).thenReturn(pending);
        assertThat(groupController.groupMembers(88L, 0).getData()).isSameAs(pending);
        verify(groupService).approvedUsers(88L, 0);

        when(groupService.getMembership(88L)).thenReturn(membership);
        assertThat(groupController.membership(88L).getData()).isSameAs(membership);
        verify(groupService).getMembership(88L);

        UpdateMemberRoleDto roleDto = new UpdateMemberRoleDto();
        roleDto.setRole(GroupMemberRole.ADMIN);
        Result<Void> roleUpdated = groupController.updateMemberRole(88L, 9L, roleDto);
        assertThat(roleUpdated.getCode()).isEqualTo(200);
        verify(groupService).updateMemberRole(88L, 9L, GroupMemberRole.ADMIN);

        Result<Void> kicked = groupController.kickMember(88L, 9L);
        assertThat(kicked.getCode()).isEqualTo(200);
        verify(groupService).kickMember(88L, 9L);

        ApproveMemberDto approveDto = new ApproveMemberDto();
        approveDto.setApproved(true);
        approveDto.setContext("欢迎加入");
        Result<Void> approved = groupController.approveMember(123L, approveDto);
        assertThat(approved.getCode()).isEqualTo(200);
        verify(groupService).approveMember(123L, "欢迎加入", true);

        SendGroupBroadcastDto broadcastDto = new SendGroupBroadcastDto();
        broadcastDto.setContext("今晚八点刷题");
        Result<Void> broadcast = groupController.broadcast(88L, broadcastDto);
        assertThat(broadcast.getCode()).isEqualTo(200);
        verify(groupBroadcastService).sendGroupBroadCastService("今晚八点刷题", 88L);
    }

    @Test
    @DisplayName("审批接口：必须显式传 approved，漏传不能默认成拒绝")
    void approveRequiresExplicitDecision() {
        ApproveMemberDto dto = new ApproveMemberDto();

        BizException missing = catchThrowableOfType(
                () -> groupController.approveMember(123L, dto), BizException.class);
        assertThat(missing.getCode()).isEqualTo(QuizNestErrorCode.APPROVE_DECISION_REQUIRED.getCode());

        BizException missingBody = catchThrowableOfType(
                () -> groupController.approveMember(123L, null), BizException.class);
        assertThat(missingBody.getCode()).isEqualTo(QuizNestErrorCode.APPROVE_DECISION_REQUIRED.getCode());
        verifyNoInteractions(groupService);
    }

    @Test
    @DisplayName("限流护栏：每条对外路由都有正的 @RateLimit（default-limit=-1 时漏标等于不限流）")
    void everyRouteHasExplicitPositiveRateLimit() {
        assertThat(GroupController.class.getAnnotation(RequestMapping.class)).isNotNull();

        int routes = 0;
        for (Method method : GroupController.class.getDeclaredMethods()) {
            if (!isRoute(method)) {
                continue;
            }
            routes++;
            RateLimit rateLimit = method.getAnnotation(RateLimit.class);
            assertThat(rateLimit)
                    .as("路由 %s 缺少 @RateLimit", method.getName())
                    .isNotNull();
            assertThat(rateLimit.limit())
                    .as("路由 %s 的 limit 必须为正数（-1 会退回全局默认，而全局是 -1）", method.getName())
                    .isPositive();
            assertThat(rateLimit.window()).as("路由 %s 的 window", method.getName()).isPositive();
        }
        assertThat(routes).as("应扫描到 19 条路由").isEqualTo(19);
    }

    private boolean isRoute(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}

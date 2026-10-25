package com.example.servicequiznest.controller;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.dto.JoinGroupDto;
import com.example.servicequiznest.dto.SendJoinTokenDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.service.GroupQuestionService;
import com.example.servicequiznest.service.GroupService;
import com.example.servicequiznest.vo.GroupQuestionVo;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.CursorPageResult;
import org.example.serviceapi.dto.Result;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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
        Result<Boolean> joined = groupController.joinGroup(88L, joinDto);
        assertThat(joined.getCode()).isEqualTo(200);
        verify(groupService).joinGroup(88L, "012345");
        groupController.joinGroup(88L, null);
        verify(groupService).joinGroup(88L, null);

        SendJoinTokenDto sendDto = new SendJoinTokenDto();
        sendDto.setToUserId(9L);
        sendDto.setContent("一起刷题");
        Result<Void> sent = groupController.sendJoinToken(88L, sendDto);
        assertThat(sent.getCode()).isEqualTo(200);
        verify(groupService).sendJoinTokenById(88L, 9L, "一起刷题");
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
        assertThat(routes).as("应扫描到 12 条路由").isEqualTo(12);
    }

    private boolean isRoute(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}

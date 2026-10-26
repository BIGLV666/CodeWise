package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组判题记录游标分页：不按题目过滤、按 group_submit_record_id 倒序、
 * 本页 submit_record 与用户信息各批量查询一次，并覆盖孤儿索引、用户缺失、
 * 非组员等边界。
 */
@ExtendWith(MockitoExtension.class)
class SubmitRecordServiceTest {

    private static final long GROUP_ID = 88L;
    private static final long USER_ID = 42L;

    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private GroupSubmitRecordMapper groupSubmitRecordMapper;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private GroupQuestionPermissionService groupQuestionPermissionService;

    @InjectMocks
    private SubmitRecordService submitRecordService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("首页：按 id 倒序返回，批量查提交记录与用户信息各一次")
    void firstPageAssemblesWithBatchQueries() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(300L, 30L, 1L),
                groupRecord(200L, 20L, 2L),
                groupRecord(100L, 10L, 1L)));
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(30L, 7L, 1L),
                submitRecord(20L, 8L, 2L)));
        when(userFeignClient.getUserList(any())).thenReturn(Result.success(Map.of(
                1L, user(1L, null, "a1.png"),
                2L, user(2L, "nick2", "a2.png"))));

        CursorPageResult<MiniGroupSubmitRecordVo> page =
                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 2);

        assertThat(page.getHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(200L);
        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getRecords())
                .extracting(MiniGroupSubmitRecordVo::groupSubmitRecordId)
                .containsExactly(300L, 200L);
        // 没有昵称时回退 userName
        assertThat(page.getRecords().get(0).nickName()).isEqualTo("user1");
        assertThat(page.getRecords().get(0).avatarUrl()).isEqualTo("a1.png");
        assertThat(page.getRecords().get(1).nickName()).isEqualTo("nick2");
        verify(submitRecordMapper).selectBatchIds(
                argThat(ids -> ids.size() == 2 && ids.contains(30L) && ids.contains(20L)));
        verify(userFeignClient).getUserList(
                argThat(ids -> ids.size() == 2 && ids.contains(1L) && ids.contains(2L)));
        verify(groupQuestionPermissionService).assertApprovedGroupMember(GROUP_ID);
    }

    @Test
    @DisplayName("最后一页：hasNext=false，nextCursor=null，记录顺序保持倒序")
    void lastPageHasNoNextCursor() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(200L, 20L, 1L),
                groupRecord(100L, 10L, 1L)));
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(20L, 8L, 1L),
                submitRecord(10L, 7L, 1L)));
        when(userFeignClient.getUserList(any())).thenReturn(Result.success(Map.of(
                1L, user(1L, "nick1", "a1.png"))));

        CursorPageResult<MiniGroupSubmitRecordVo> page =
                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, 300L, 2);

        assertThat(page.getHasNext()).isFalse();
        assertThat(page.getNextCursor()).isNull();
        assertThat(page.getRecords())
                .extracting(MiniGroupSubmitRecordVo::submitRecordId)
                .containsExactly(20L, 10L);
    }

    @Test
    @DisplayName("空页：不触发提交记录/用户批量查询")
    void emptyPageSkipsBatchQueries() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(Collections.emptyList());

        CursorPageResult<MiniGroupSubmitRecordVo> page =
                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 20);

        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getHasNext()).isFalse();
        assertThat(page.getNextCursor()).isNull();
        verifyNoInteractions(submitRecordMapper, userFeignClient);
    }

    @Test
    @DisplayName("孤儿索引：跳过输出但游标仍推进，且不为脏数据查用户")
    void staleGroupRecordIsSkippedAndCursorStillAdvances() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(300L, 30L, 1L),
                groupRecord(200L, 20L, 2L),
                groupRecord(100L, 10L, 1L)));
        // 30L 对应的 submit_record 已不存在
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(20L, 8L, 2L)));
        when(userFeignClient.getUserList(any())).thenReturn(Result.success(Map.of(
                2L, user(2L, "nick2", "a2.png"))));

        CursorPageResult<MiniGroupSubmitRecordVo> page =
                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 2);

        assertThat(page.getHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(200L);
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().getFirst().groupSubmitRecordId()).isEqualTo(200L);
        verify(userFeignClient).getUserList(argThat(ids -> ids.size() == 1 && ids.contains(2L)));
    }

    @Test
    @DisplayName("用户已删除：昵称/头像降级为 null，不影响整页")
    void missingUserDegradesToNullProfile() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(300L, 30L, 1L)));
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(30L, 7L, 1L)));
        when(userFeignClient.getUserList(any())).thenReturn(Result.success(Collections.emptyMap()));

        CursorPageResult<MiniGroupSubmitRecordVo> page =
                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 20);

        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().getFirst().nickName()).isNull();
        assertThat(page.getRecords().getFirst().avatarUrl()).isNull();
    }

    @Test
    @DisplayName("用户服务异常：显式失败，不返回半成品列表")
    void userServiceFailureMapsToDownstream() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(300L, 30L, 1L)));
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(30L, 7L, 1L)));
        RuntimeException cause = new RuntimeException("connect timeout");
        when(userFeignClient.getUserList(any())).thenThrow(cause);

        assertThatThrownBy(() -> submitRecordService
                .cursorMiniGroupSubmitRecord(GROUP_ID, null, 20))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
                    assertThat(e.getCause()).isSameAs(cause);
                });
    }

    @Test
    @DisplayName("非组员：权限校验失败，不查小组记录")
    void nonMemberRejectedBeforeQuery() {
        doThrow(new BizException(QuestionErrorCode.GROUP_SUBMIT_RECORD_VIEW_FORBIDDEN))
                .when(groupQuestionPermissionService).assertApprovedGroupMember(GROUP_ID);

        assertThatThrownBy(() -> submitRecordService
                .cursorMiniGroupSubmitRecord(GROUP_ID, null, 20))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getCode())
                                .isEqualTo(QuestionErrorCode.GROUP_SUBMIT_RECORD_VIEW_FORBIDDEN.getCode()));
        verifyNoInteractions(groupSubmitRecordMapper, submitRecordMapper, userFeignClient);
    }

    @Test
    @DisplayName("同一用户的多条记录只批量查一次用户")
    void sameUserQueriedOnce() {
        when(groupSubmitRecordMapper.selectList(any())).thenReturn(List.of(
                groupRecord(300L, 30L, 1L),
                groupRecord(200L, 20L, 1L)));
        when(submitRecordMapper.selectBatchIds(any())).thenReturn(List.of(
                submitRecord(30L, 7L, 1L),
                submitRecord(20L, 8L, 1L)));
        Map<Long, UserDto> users = new HashMap<>();
        users.put(1L, user(1L, "nick1", "a1.png"));
        when(userFeignClient.getUserList(any())).thenReturn(Result.success(users));

        submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 20);

        verify(userFeignClient).getUserList(argThat(ids -> ids.size() == 1 && ids.contains(1L)));
    }

    @Test
    @DisplayName("pageSize：空/非正数取 20，超过 50 截断")
    void pageSizeIsNormalized() {
        assertThat(SubmitRecordService.normalizePageSize(null)).isEqualTo(20);
        assertThat(SubmitRecordService.normalizePageSize(0)).isEqualTo(20);
        assertThat(SubmitRecordService.normalizePageSize(-1)).isEqualTo(20);
        assertThat(SubmitRecordService.normalizePageSize(30)).isEqualTo(30);
        assertThat(SubmitRecordService.normalizePageSize(100)).isEqualTo(50);
    }

    private static GroupSubmitRecord groupRecord(long groupSubmitRecordId,
                                                long submitRecordId,
                                                long userId) {
        return GroupSubmitRecord.builder()
                .groupSubmitRecordId(groupSubmitRecordId)
                .submitRecordId(submitRecordId)
                .groupId(GROUP_ID)
                .userId(userId)
                .createTime(LocalDateTime.now())
                .build();
    }

    private static SubmitRecord submitRecord(long submitRecordId, long questionId, long userId) {
        SubmitRecord record = SubmitRecord.builder()
                .submitRecordId(submitRecordId)
                .questionId(questionId)
                .questionTitle("题目" + questionId)
                .userId(userId)
                .build();
        record.setJudgeStatus("AC");
        return record;
    }

    private static UserDto user(long userId, String nickName, String avatarUrl) {
        UserDto user = new UserDto();
        user.setUserId(String.valueOf(userId));
        user.setUserName("user" + userId);
        user.setNickName(nickName);
        user.setAvatarUrl(avatarUrl);
        return user;
    }
}

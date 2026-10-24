package com.example.servicequiznest.service;

import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GroupQuestionService#deleteGroupQuestion} 的平台/自建分支与归属校验。
 *
 * <p>回归两个严重缺陷：平台题分支缺少 {@code return} 会继续调用题目服务删除题目本体；
 * 未校验索引归属会允许 A 组管理员跨组删除 B 组索引。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionServiceDeleteTest {

    private static final long GROUP_ID = 88L;
    private static final long OTHER_GROUP_ID = 99L;
    private static final long USER_ID = 9L;
    private static final long MAPPING_ID = 100L;
    private static final long QUESTION_ID = 7L;
    private static final String INTERNAL_TOKEN = "test-token";

    @Mock
    private QuestionFeignClient questionFeignClient;

    @Mock
    private GroupMemberMapper groupMemberMapper;

    @Mock
    private GroupQuestionMapper groupQuestionMapper;

    @Mock
    private GroupQuestionRollbackPublisher rollbackPublisher;

    @Mock
    private GroupQuestionCleanupPublisher cleanupPublisher;

    private GroupQuestionService groupQuestionService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        groupQuestionService = new GroupQuestionService(
                questionFeignClient, groupMemberMapper, groupQuestionMapper,
                rollbackPublisher, cleanupPublisher, INTERNAL_TOKEN);
        // 参数非法用例会在成员校验前返回，这里用 lenient 避免无用的严格桩报错
        lenient().when(groupMemberMapper.selectOne(any())).thenReturn(adminMember());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("平台题：只删小组索引，绝不调用题目服务删除题目本体")
    void platformQuestionOnlyDeletesIndex() {
        when(groupQuestionMapper.selectById(MAPPING_ID))
                .thenReturn(mapping(MAPPING_ID, GROUP_ID, QUESTION_ID, 1));
        when(groupQuestionMapper.deleteById(MAPPING_ID)).thenReturn(1);

        groupQuestionService.deleteGroupQuestion(MAPPING_ID, GROUP_ID);

        verify(groupQuestionMapper).deleteById(MAPPING_ID);
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("自建题：先删题目服务，再删本地索引")
    void customQuestionDeletesRemoteThenIndex() {
        when(groupQuestionMapper.selectById(MAPPING_ID))
                .thenReturn(mapping(MAPPING_ID, GROUP_ID, QUESTION_ID, 0));
        when(questionFeignClient.deleteQuestion(INTERNAL_TOKEN, QUESTION_ID))
                .thenReturn(Result.success("success"));
        when(groupQuestionMapper.deleteById(MAPPING_ID)).thenReturn(1);

        groupQuestionService.deleteGroupQuestion(MAPPING_ID, GROUP_ID);

        verify(questionFeignClient).deleteQuestion(INTERNAL_TOKEN, QUESTION_ID);
        verify(groupQuestionMapper).deleteById(MAPPING_ID);
    }

    @Test
    @DisplayName("跨组删除：索引不属于当前小组时拒绝，不删索引也不调题目服务")
    void crossGroupDeletionRejected() {
        when(groupQuestionMapper.selectById(MAPPING_ID))
                .thenReturn(mapping(MAPPING_ID, OTHER_GROUP_ID, QUESTION_ID, 1));

        assertCode(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        verify(groupQuestionMapper, never()).deleteById(anyLong());
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("索引不存在：返回未找到，不产生任何写操作")
    void missingMappingRejected() {
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(null);

        assertCode(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        verify(groupQuestionMapper, never()).deleteById(anyLong());
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("索引主键为空/非正数：参数非法，不查库")
    void invalidMappingIdRejected() {
        assertCode(null, GROUP_ID, QuizNestErrorCode.INVALID_QUESTION_ID);
        assertCode(0L, GROUP_ID, QuizNestErrorCode.INVALID_QUESTION_ID);
        assertCode(-1L, GROUP_ID, QuizNestErrorCode.INVALID_QUESTION_ID);

        verifyNoInteractions(groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("平台题索引删除失败：返回删除失败，不继续调题目服务")
    void platformIndexDeleteFailureStops() {
        when(groupQuestionMapper.selectById(MAPPING_ID))
                .thenReturn(mapping(MAPPING_ID, GROUP_ID, QUESTION_ID, 1));
        when(groupQuestionMapper.deleteById(MAPPING_ID)).thenReturn(0);

        assertCode(MAPPING_ID, GROUP_ID, QuizNestErrorCode.DELETE_GROUP_QUESTION_FAILED);

        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("普通成员：无删除权限")
    void plainMemberCannotDelete() {
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.MEMBER);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);

        assertCode(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NO_PERMISSION_ADD_QUESTION);

        verifyNoInteractions(groupQuestionMapper, questionFeignClient);
    }

    private void assertCode(Long mappingId, Long groupId,
                            org.example.serviceapi.exception.ErrorCode expected) {
        assertThatThrownBy(() -> groupQuestionService.deleteGroupQuestion(mappingId, groupId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }

    private static GroupMember adminMember() {
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.ADMIN);
        return member;
    }

    private static GroupQuestion mapping(long mappingId, long groupId, long questionId, int isPlatform) {
        return GroupQuestion.builder()
                .groupQuestionId(mappingId)
                .groupId(groupId)
                .questionId(questionId)
                .isPlatFromQuestion(isPlatform)
                .build();
    }
}

package com.example.servicequiznest.service;

import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import com.example.servicequiznest.vo.GroupQuestionVo;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GroupQuestionService#getQuestion} / {@link GroupQuestionService#updateGroupQuestion}
 * 的归属校验、平台题保护与下游错误翻译。
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionServiceDetailUpdateTest {

    private static final long GROUP_ID = 88L;
    private static final long OTHER_GROUP_ID = 99L;
    private static final long USER_ID = 9L;
    private static final long MAPPING_ID = 100L;
    private static final long QUESTION_ID = 7L;
    private static final String INTERNAL_TOKEN = "test-internal-token";

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
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("详情：空 id 按缺失，非正数按非法 id，均不查库")
    void getQuestionRejectsInvalidIds() {
        assertCodeGet(null, GROUP_ID, CommonErrorCode.PARAM_MISSING);
        assertCodeGet(MAPPING_ID, null, CommonErrorCode.PARAM_MISSING);
        assertCodeGet(MAPPING_ID, 0L, QuizNestErrorCode.INVALID_GROUP_ID);
        assertCodeGet(0L, GROUP_ID, QuizNestErrorCode.INVALID_QUESTION_ID);
        verifyNoInteractions(groupMemberMapper, groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("详情：非成员/待审批成员拒绝，不查索引")
    void getQuestionRejectsNonApprovedMember() {
        when(groupMemberMapper.selectOne(any())).thenReturn(null);
        assertCodeGet(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);

        GroupMember pending = new GroupMember();
        pending.setJoinStatus(GroupJoinStatus.PENDING);
        when(groupMemberMapper.selectOne(any())).thenReturn(pending);
        assertCodeGet(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);

        verifyNoInteractions(groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("详情：索引不存在或不属于当前小组时拒绝，防止跨组读取")
    void getQuestionRejectsCrossGroupMapping() {
        stubApprovedMember();
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(null);
        assertCodeGet(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(OTHER_GROUP_ID, QUESTION_ID, 0));
        assertCodeGet(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("详情：题目服务非 200 按下游不可用，且不懒删除索引")
    void getQuestionDownstreamFailureDoesNotDeleteMapping() {
        stubApprovedMember();
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 0));
        when(questionFeignClient.getQuestionInfo(QUESTION_ID))
                .thenReturn(null)
                .thenReturn(Result.error("down", 500));

        assertCodeGet(MAPPING_ID, GROUP_ID, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCodeGet(MAPPING_ID, GROUP_ID, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);

        verify(groupQuestionMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("详情：题目已不存在时懒删除本地索引并返回未找到")
    void getQuestionLazyDeletesMissingQuestion() {
        stubApprovedMember();
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 0));
        when(questionFeignClient.getQuestionInfo(QUESTION_ID)).thenReturn(Result.success((QuestionDto) null));

        assertCodeGet(MAPPING_ID, GROUP_ID, QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        verify(groupQuestionMapper).deleteByIds(java.util.List.of(MAPPING_ID));
    }

    @Test
    @DisplayName("详情：成功返回题目与小组索引 id")
    void getQuestionReturnsDetail() {
        stubApprovedMember();
        QuestionDto question = QuestionDto.builder().questionId(QUESTION_ID).title("题").build();
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 0));
        when(questionFeignClient.getQuestionInfo(QUESTION_ID)).thenReturn(Result.success(question));

        GroupQuestionVo vo = groupQuestionService.getQuestion(MAPPING_ID, GROUP_ID);

        assertThat(vo.questionDto()).isSameAs(question);
        assertThat(vo.groupQuestionId()).isEqualTo(MAPPING_ID);
    }

    @Test
    @DisplayName("修改：空 id/空 DTO/缺标题在查库前拒绝")
    void updateQuestionRejectsInvalidInputBeforeDatabase() {
        assertCodeUpdate(null, MAPPING_ID, GROUP_ID, CommonErrorCode.PARAM_MISSING);
        assertCodeUpdate(validUpdate().build(), MAPPING_ID, 0L, QuizNestErrorCode.INVALID_GROUP_ID);
        assertCodeUpdate(validUpdate().build(), 0L, GROUP_ID, QuizNestErrorCode.INVALID_QUESTION_ID);
        assertCodeUpdate(validUpdate().title(" ").build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.QUESTION_TITLE_REQUIRED);

        verifyNoInteractions(groupMemberMapper, groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("修改：跨组索引拒绝，不调题目服务")
    void updateQuestionRejectsCrossGroupMapping() {
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(OTHER_GROUP_ID, QUESTION_ID, 0));

        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);

        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("修改：平台题目不允许在小组内修改")
    void updateQuestionRejectsPlatformQuestion() {
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 1));

        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.NOT_ALLOWED_UPDATE_PLATFORM_QUESTION);

        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("修改：普通成员无权限")
    void updateQuestionRejectsPlainMember() {
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 0));
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.MEMBER);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);

        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.NO_PERMISSION_ADD_QUESTION);

        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("修改：使用索引里的 questionId，忽略请求体里的 questionId")
    void updateQuestionUsesTrustedQuestionId() {
        stubAdminAndMapping();
        when(questionFeignClient.putQuestion(eq(INTERNAL_TOKEN), any())).thenReturn(
                Result.success(QuestionDto.builder().questionId(QUESTION_ID).build()));

        groupQuestionService.updateGroupQuestion(
                validUpdate().questionId(999L).title("改后标题").build(), MAPPING_ID, GROUP_ID);

        ArgumentCaptor<org.example.serviceapi.dto.question.UpdateGroupQuestionDto> captor =
                ArgumentCaptor.forClass(org.example.serviceapi.dto.question.UpdateGroupQuestionDto.class);
        verify(questionFeignClient).putQuestion(eq(INTERNAL_TOKEN), captor.capture());
        assertThat(captor.getValue().questionId()).isEqualTo(QUESTION_ID);
        assertThat(captor.getValue().title()).isEqualTo("改后标题");
    }

    @Test
    @DisplayName("修改：下游 null/非 200/异常统一按下游不可用")
    void updateQuestionDownstreamFailure() {
        stubAdminAndMapping();
        when(questionFeignClient.putQuestion(eq(INTERNAL_TOKEN), any()))
                .thenReturn(null)
                .thenReturn(Result.error("down", 500))
                .thenThrow(new RuntimeException("timeout"));

        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("修改：下游业务码翻译成本服务错误码")
    void updateQuestionTranslatesDownstreamCodes() {
        stubAdminAndMapping();
        when(questionFeignClient.putQuestion(eq(INTERNAL_TOKEN), any()))
                .thenReturn(Result.error("not editable", 4030203))
                .thenReturn(Result.error("not found", 4040201))
                .thenReturn(Result.error("duplicate", 4090208));

        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.NOT_ALLOWED_UPDATE_PLATFORM_QUESTION);
        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.NOTFOUND_QUESTION);
        assertCodeUpdate(validUpdate().build(), MAPPING_ID, GROUP_ID,
                QuizNestErrorCode.QUESTION_CONTENT_DUPLICATED);
    }

    private void stubApprovedMember() {
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.MEMBER);
        member.setJoinStatus(GroupJoinStatus.APPROVED);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);
    }

    private void stubAdminAndMapping() {
        when(groupQuestionMapper.selectById(MAPPING_ID)).thenReturn(mapping(GROUP_ID, QUESTION_ID, 0));
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.ADMIN);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);
    }

    private static GroupQuestion mapping(long groupId, long questionId, int isPlatform) {
        return GroupQuestion.builder()
                .groupQuestionId(MAPPING_ID)
                .groupId(groupId)
                .questionId(questionId)
                .isPlatFromQuestion(isPlatform)
                .build();
    }

    private static UpdateGroupQuestionDto.UpdateGroupQuestionDtoBuilder validUpdate() {
        return UpdateGroupQuestionDto.builder()
                .questionId(QUESTION_ID)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .inputDesc("两个整数")
                .outputDesc("它们的和")
                .sampleInput("1 2")
                .sampleOutput("3")
                .timeLimit(1000)
                .memoryLimit(256);
    }

    private void assertCodeGet(Long mappingId, Long groupId, ErrorCode expected) {
        assertThatThrownBy(() -> groupQuestionService.getQuestion(mappingId, groupId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }

    private void assertCodeUpdate(UpdateGroupQuestionDto dto, Long mappingId, Long groupId, ErrorCode expected) {
        assertThatThrownBy(() -> groupQuestionService.updateGroupQuestion(dto, mappingId, groupId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

package com.example.servicequiznest.service;

import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
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
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GroupQuestionService#addQuestion} 的入参校验与自建题目补偿链路。
 *
 * <p>覆盖两类不变量：</p>
 * <ol>
 *   <li>校验必须在查成员/调 Feign 之前完成，非法请求不产生任何 IO；</li>
 *   <li>题目服务建题成功但本地 {@code group_question} 写入失败时，必须发布回滚事件，
 *       且回滚发布失败不能覆盖原始失败。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionServiceValidationTest {

    private static final String INTERNAL_TOKEN = "test-internal-token";
    private static final long GROUP_ID = 88L;
    private static final long USER_ID = 9L;

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
    @DisplayName("groupId 为空：直接拒绝，不查成员也不调 Feign")
    void groupIdRequired() {
        CreateGroupQuestionDto dto = validCustom().groupId(null).build();

        assertCode(dto, QuizNestErrorCode.GROUP_ID_REQUIRED);
        verifyNoInteractions(groupMemberMapper, questionFeignClient, groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("平台题目：不要求标题/描述等自建字段，直接建索引")
    void platformQuestionIgnoresCustomFields() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.getQuestionInfo(7L))
                .thenReturn(Result.success(visibleQuestion(7L, 1, 999L)));
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenReturn(1);

        groupQuestionService.addQuestion(CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID)
                .questionId(7L)
                .build());

        ArgumentCaptor<GroupQuestion> captor = ArgumentCaptor.forClass(GroupQuestion.class);
        verify(groupQuestionMapper).insert(captor.capture());
        assertThat(captor.getValue().getGroupId()).isEqualTo(GROUP_ID);
        assertThat(captor.getValue().getQuestionId()).isEqualTo(7L);
        assertThat(captor.getValue().getIsPlatFromQuestion()).isEqualTo(1);
        assertThat(captor.getValue().getCreateUserId()).isEqualTo(USER_ID);
        verifyNoInteractions(rollbackPublisher);
    }

    @Test
    @DisplayName("平台题目 id 非正数：按参数非法拒绝")
    void platformQuestionIdInvalid() {
        assertCode(CreateGroupQuestionDto.builder().groupId(GROUP_ID).questionId(0L).build(),
                CommonErrorCode.PARAM_INVALID);
        assertCode(CreateGroupQuestionDto.builder().groupId(GROUP_ID).questionId(-1L).build(),
                CommonErrorCode.PARAM_INVALID);
        verifyNoInteractions(groupMemberMapper, questionFeignClient, groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("平台题目可见性：公开题任何人可加，私密题仅创建人可加")
    void platformQuestionVisibilityAllowsPublicAndOwnPrivate() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.getQuestionInfo(1L))
                .thenReturn(Result.success(visibleQuestion(1L, 1, 999L)));
        when(questionFeignClient.getQuestionInfo(2L))
                .thenReturn(Result.success(visibleQuestion(2L, 3, USER_ID)));
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenReturn(1);

        groupQuestionService.addQuestion(CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID).questionId(1L).build());
        groupQuestionService.addQuestion(CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID).questionId(2L).build());

        verify(groupQuestionMapper, times(2)).insert(any(GroupQuestion.class));
    }

    @Test
    @DisplayName("平台题目不可见：他人私密/下架/审核中/状态缺失一律拒绝且不写索引")
    void platformQuestionInvisibleRejected() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.getQuestionInfo(11L))
                .thenReturn(Result.success(visibleQuestion(11L, 3, 999L)));
        when(questionFeignClient.getQuestionInfo(12L))
                .thenReturn(Result.success(visibleQuestion(12L, 0, USER_ID)));
        when(questionFeignClient.getQuestionInfo(13L))
                .thenReturn(Result.success(visibleQuestion(13L, 2, USER_ID)));
        when(questionFeignClient.getQuestionInfo(14L))
                .thenReturn(Result.success(visibleQuestion(14L, null, USER_ID)));

        for (long questionId = 11L; questionId <= 14L; questionId++) {
            assertCode(CreateGroupQuestionDto.builder()
                            .groupId(GROUP_ID).questionId(questionId).build(),
                    QuizNestErrorCode.NOTFOUND_QUESTION);
        }
        verify(groupQuestionMapper, never()).insert(any(GroupQuestion.class));
    }

    @Test
    @DisplayName("自建题目：标题、描述必填（空白也算没填）")
    void customQuestionRequiresTitleAndDescription() {
        assertCode(validCustom().title(null).build(), QuizNestErrorCode.QUESTION_TITLE_REQUIRED);
        assertCode(validCustom().title("   ").build(), QuizNestErrorCode.QUESTION_TITLE_REQUIRED);
        assertCode(validCustom().description(null).build(), QuizNestErrorCode.QUESTION_CONTENT_REQUIRED);
        assertCode(validCustom().description("").build(), QuizNestErrorCode.QUESTION_CONTENT_REQUIRED);
        verifyNoInteractions(groupMemberMapper, questionFeignClient, groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("自建题目：标题/标签按字符数限长，TEXT 字段按 UTF-8 字节数限长")
    void customQuestionLengthLimits() {
        assertCode(validCustom().title("标".repeat(201)).build(),
                QuizNestErrorCode.QUESTION_TITLE_TOO_LONG);
        assertCode(validCustom().tags("t".repeat(256)).build(),
                QuizNestErrorCode.QUESTION_TAGS_TOO_LONG);
        assertCode(validCustom().hint("a".repeat(65_536)).build(),
                QuizNestErrorCode.QUESTION_TEXT_TOO_LONG);
        verifyNoInteractions(groupMemberMapper, questionFeignClient, groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("自建题目：时间/内存限制必须为正数")
    void customQuestionLimitsRequired() {
        assertCode(validCustom().timeLimit(null).build(), QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID);
        assertCode(validCustom().timeLimit(0).build(), QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID);
        assertCode(validCustom().memoryLimit(null).build(), QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID);
        assertCode(validCustom().memoryLimit(-1).build(), QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID);
        verifyNoInteractions(groupMemberMapper, questionFeignClient, groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("平台题目重复添加：唯一键冲突转为业务错误，不泄露 SQL")
    void duplicatePlatformQuestionReturnsBusinessError() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.getQuestionInfo(7L))
                .thenReturn(Result.success(visibleQuestion(7L, 1, 999L)));
        when(groupQuestionMapper.insert(any(GroupQuestion.class)))
                .thenThrow(new DuplicateKeyException("uk_group_question_group_question"));

        assertCode(CreateGroupQuestionDto.builder().groupId(GROUP_ID).questionId(7L).build(),
                QuizNestErrorCode.QUESTION_ALREADY_IN_GROUP);
    }

    @Test
    @DisplayName("自建题目成功：Feign 建题后写本地索引（is_plat=0），不发布回滚")
    void customQuestionSuccessWritesLocalMapping() {
        stubMemberAndRemoteCreate(123L);
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenReturn(1);

        groupQuestionService.addQuestion(validCustom().build());

        ArgumentCaptor<QuestionDto> requestCaptor = ArgumentCaptor.forClass(QuestionDto.class);
        verify(questionFeignClient).addQuestion(eq(INTERNAL_TOKEN), requestCaptor.capture());
        QuestionDto sent = requestCaptor.getValue();
        assertThat(sent.getQuestionId()).isNull();
        assertThat(sent.getTitle()).isEqualTo("两数之和");
        assertThat(sent.getDescription()).isEqualTo("给定两个整数，输出它们的和");
        assertThat(sent.getTimeLimit()).isEqualTo(1000);
        assertThat(sent.getMemoryLimit()).isEqualTo(256);

        ArgumentCaptor<GroupQuestion> mappingCaptor = ArgumentCaptor.forClass(GroupQuestion.class);
        verify(groupQuestionMapper).insert(mappingCaptor.capture());
        GroupQuestion mapping = mappingCaptor.getValue();
        assertThat(mapping.getQuestionId()).isEqualTo(123L);
        assertThat(mapping.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(mapping.getCreateUserId()).isEqualTo(USER_ID);
        assertThat(mapping.getIsPlatFromQuestion()).isZero();
        verifyNoInteractions(rollbackPublisher);
    }

    @Test
    @DisplayName("本地 insert 返回 0：发布回滚并抛添加失败")
    void localInsertReturnsZeroPublishesRollback() {
        stubMemberAndRemoteCreate(123L);
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenReturn(0);

        assertCode(validCustom().build(), QuizNestErrorCode.ADD_QUESTION_FAILED);

        verify(rollbackPublisher).publish(123L, GROUP_ID);
    }

    @Test
    @DisplayName("本地 insert 抛异常：发布回滚并保留原始 cause")
    void localInsertThrowsPublishesRollback() {
        stubMemberAndRemoteCreate(123L);
        RuntimeException dbError = new RuntimeException("db down");
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenThrow(dbError);

        assertThatThrownBy(() -> groupQuestionService.addQuestion(validCustom().build()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode())
                            .isEqualTo(QuizNestErrorCode.ADD_QUESTION_FAILED.getCode());
                    assertThat(e.getCause()).isSameAs(dbError);
                });

        verify(rollbackPublisher).publish(123L, GROUP_ID);
    }

    @Test
    @DisplayName("Feign 返回空数据：不写本地、不发布回滚（题目服务自身已回滚）")
    void feignReturnsEmptyDataDoesNotPublishRollback() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.addQuestion(eq(INTERNAL_TOKEN), any(QuestionDto.class)))
                .thenReturn(null)
                .thenReturn(Result.success((QuestionDto) null));

        assertCode(validCustom().build(), QuizNestErrorCode.ADD_QUESTION_FAILED);
        assertCode(validCustom().build(), QuizNestErrorCode.ADD_QUESTION_FAILED);

        verifyNoInteractions(groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("Feign 调用抛异常：不写本地、不发布回滚")
    void feignThrowsDoesNotPublishRollback() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        RuntimeException timeout = new RuntimeException("timeout");
        when(questionFeignClient.addQuestion(eq(INTERNAL_TOKEN), any(QuestionDto.class)))
                .thenThrow(timeout);

        assertThatThrownBy(() -> groupQuestionService.addQuestion(validCustom().build()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode())
                            .isEqualTo(QuizNestErrorCode.ADD_QUESTION_FAILED.getCode());
                    assertThat(e.getCause()).isSameAs(timeout);
                });

        verifyNoInteractions(groupQuestionMapper, rollbackPublisher);
    }

    @Test
    @DisplayName("回滚事件发布失败：仍按添加失败返回，不把 outbox 异常透给用户")
    void rollbackPublishFailureDoesNotMaskOriginalFailure() {
        stubMemberAndRemoteCreate(123L);
        when(groupQuestionMapper.insert(any(GroupQuestion.class))).thenReturn(0);
        doThrow(new RuntimeException("outbox down"))
                .when(rollbackPublisher).publish(anyLong(), anyLong());

        assertCode(validCustom().build(), QuizNestErrorCode.ADD_QUESTION_FAILED);

        verify(rollbackPublisher).publish(123L, GROUP_ID);
    }

    @Test
    @DisplayName("并发：成功/失败请求互不串扰，失败各自按自己的 questionId 发布回滚")
    void concurrentAddsPublishRollbackPerFailedRequest() throws Exception {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        AtomicInteger sequence = new AtomicInteger();
        when(questionFeignClient.addQuestion(eq(INTERNAL_TOKEN), any(QuestionDto.class)))
                .thenAnswer(invocation -> Result.success(
                        QuestionDto.builder().questionId((long) sequence.incrementAndGet()).build()));
        when(groupQuestionMapper.insert(any(GroupQuestion.class)))
                .thenAnswer(invocation -> {
                    GroupQuestion mapping = invocation.getArgument(0);
                    // 偶数 questionId 本地写入成功，奇数失败并应补偿
                    return mapping.getQuestionId() % 2 == 0 ? 1 : 0;
                });

        int total = 60;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                long groupId = 1000L + i;
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(USER_ID);
                    try {
                        groupQuestionService.addQuestion(validCustom().groupId(groupId).build());
                    } catch (BizException expected) {
                        assertThat(expected.getCode())
                                .isEqualTo(QuizNestErrorCode.ADD_QUESTION_FAILED.getCode());
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(groupQuestionMapper, times(total)).insert(any(GroupQuestion.class));
        ArgumentCaptor<Long> rollbackIds = ArgumentCaptor.forClass(Long.class);
        verify(rollbackPublisher, times(total / 2)).publish(rollbackIds.capture(), anyLong());
        assertThat(rollbackIds.getAllValues()).allMatch(id -> id % 2 == 1);
    }

    /** 只 stub 成员校验与远程建题，本地 insert 由各用例自行决定成功/失败。 */
    private void stubMemberAndRemoteCreate(long questionId) {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(questionFeignClient.addQuestion(eq(INTERNAL_TOKEN), any(QuestionDto.class)))
                .thenReturn(Result.success(QuestionDto.builder().questionId(questionId).build()));
    }

    private static CreateGroupQuestionDto.CreateGroupQuestionDtoBuilder validCustom() {
        return CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .inputDesc("两个整数")
                .outputDesc("它们的和")
                .sampleInput("1 2")
                .sampleOutput("3")
                .timeLimit(1000)
                .memoryLimit(256);
    }

    private static QuestionDto visibleQuestion(long questionId, Integer status, Long createUserId) {
        return QuestionDto.builder()
                .questionId(questionId)
                .status(status)
                .createUserId(createUserId)
                .build();
    }

    private static GroupMember rootMember() {
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.ROOT);
        return member;
    }

    private void assertCode(CreateGroupQuestionDto dto, org.example.serviceapi.exception.ErrorCode expected) {
        assertThatThrownBy(() -> groupQuestionService.addQuestion(dto))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

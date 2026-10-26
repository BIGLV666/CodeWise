package org.example.servicequestion.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JudgeService 事务性 Outbox（OutboxPro）单测：
 * 提交/调试不再直接发 MQ，改为在事务内 publish Outbox 事件（路由由 EventRegistry 解析）。
 *
 * <p>新增的小组提交必须同时满足「题目在组内 + 用户是组员」，即使是公开题也不能
 * 绕过；本测试覆盖参数、权限、事务回滚与小组索引写入。</p>
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceTest {

    private static final long QUESTION_ID = 5L;
    private static final long GROUP_ID = 88L;
    private static final long USER_ID = 42L;

    @Mock
    private SubmitRecordMapper submitRecordMapper;

    @Mock
    private GroupSubmitRecordMapper groupSubmitRecordMapper;

    @Mock
    private OutboxProPublisher outboxPublisher;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private GroupQuestionPermissionService groupQuestionPermissionService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private JudgeService judgeService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        // 默认题目为公开题；需要私密题的用例自行覆盖
        lenient().when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(1));
        // 真实事务模板会执行回调；单测用替身模拟执行，并允许用例覆盖以捕获回滚状态
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "REVIEW", "PLAN"})
    void judgeAcceptsAllSupportedSubmitScenes(String scene) {
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(900L);
            return 1;
        });

        GetCodeDto dto = request(scene);

        assertEquals(900L, judgeService.judge(dto));
        ArgumentCaptor<SubmitRecord> captor = ArgumentCaptor.forClass(SubmitRecord.class);
        verify(submitRecordMapper).insert(captor.capture());
        assertEquals(scene, captor.getValue().getSubmitScene());
        verify(outboxPublisher).publish(EventTypes.JUDGE_SUBMIT_REQUEST, 900L);
        verifyNoInteractions(groupSubmitRecordMapper);
    }

    @Test
    void judgeRejectsUnsupportedSubmitScene() {
        GetCodeDto dto = request("UNKNOWN");

        BizException exception = assertThrows(BizException.class, () -> judgeService.judge(dto));
        assertEquals(QuestionErrorCode.SUBMIT_SCENE_UNSUPPORTED.getCode(), exception.getCode());
        // 场景校验先于题目查询/小组 Feign，非法请求不产生任何 IO
        verifyNoInteractions(questionMapper, groupQuestionPermissionService,
                submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    void judgePublishesSubmitRequestToOutboxAfterInsert() {
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            // 模拟 MyBatis-Plus 回填自增主键
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(777L);
            return 1;
        });

        Long submitRecordId = judgeService.judge(request("NORMAL"));

        assertEquals(777L, submitRecordId);
        verify(submitRecordMapper).insert(any(SubmitRecord.class));
        verify(outboxPublisher).publish(
                eq(EventTypes.JUDGE_SUBMIT_REQUEST),
                eq(777L));
    }

    /** 提交场景缺失时应返回明确业务错误，而不是 switch(null) 的 NPE（客户端漏传字段不得成为 500）。 */
    @Test
    void judgeRejectsMissingSubmitSceneWithBusinessError() {
        GetCodeDto getCodeDto = request(null);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(getCodeDto));
        assertEquals(QuestionErrorCode.SUBMIT_SCENE_REQUIRED.getCode(), exception.getCode());
        verifyNoInteractions(questionMapper, groupQuestionPermissionService,
                submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeRejectsNullRequest() {
        BizException exception = assertThrows(BizException.class, () -> judgeService.judge(null));

        assertEquals(CommonErrorCode.PARAM_MISSING.getCode(), exception.getCode());
        verifyNoInteractions(questionMapper, submitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeRejectsMissingQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.QUESTION_NOT_FOUND.getCode(), exception.getCode());
        verifyNoInteractions(submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeAcceptsGroupPrivateQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(3));
        when(groupQuestionPermissionService.canAccessPrivateQuestion(any(Question.class))).thenReturn(true);
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(901L);
            return 1;
        });

        assertEquals(901L, judgeService.judge(request("NORMAL")));
        verify(groupQuestionPermissionService).canAccessPrivateQuestion(any(Question.class));
        verify(submitRecordMapper).insert(any(SubmitRecord.class));
    }

    @Test
    void judgeRejectsInvisiblePrivateQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(3));
        when(groupQuestionPermissionService.canAccessPrivateQuestion(any(Question.class))).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(CommonErrorCode.RESOURCE_NOT_FOUND.getCode(), exception.getCode());
        verifyNoInteractions(submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeMapsInsertFailureToBusinessErrorAndMarksRollback() {
        TransactionStatus status = captureRollbackStatus();
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.SUBMIT_RECORD_SAVE_FAILED.getCode(), exception.getCode());
        verify(status).setRollbackOnly();
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    @DisplayName("insert 成功但未回填主键：按保存失败回滚，不能发布空 id 事件")
    void judgeRejectsInsertWithoutGeneratedId() {
        TransactionStatus status = captureRollbackStatus();
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenReturn(1);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.SUBMIT_RECORD_SAVE_FAILED.getCode(), exception.getCode());
        verify(status).setRollbackOnly();
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    @DisplayName("小组提交：缺 groupId 直接拒绝，不查库、不调用 Feign")
    void groupSubmitRejectsMissingGroupId() {
        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP")));

        assertEquals(QuestionErrorCode.GROUP_ID_REQUIRED.getCode(), exception.getCode());
        verifyNoInteractions(questionMapper, groupQuestionPermissionService,
                submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("小组提交：非法 groupId 直接拒绝")
    void groupSubmitRejectsInvalidGroupId() {
        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", 0L)));

        assertEquals(QuestionErrorCode.GROUP_ID_INVALID.getCode(), exception.getCode());
        verifyNoInteractions(questionMapper, submitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("传了 groupId 却不是 GROUP 场景：拒绝，不能静默按普通提交落库")
    void groupSubmitRejectsSceneMismatch() {
        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL", GROUP_ID)));

        assertEquals(QuestionErrorCode.GROUP_SUBMIT_SCENE_REQUIRED.getCode(), exception.getCode());
        verifyNoInteractions(questionMapper, submitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("公开题小组提交：仍然必须校验小组归属与成员身份")
    void groupSubmitRequiresMembershipEvenForPublicQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(1));
        doThrow(new BizException(QuestionErrorCode.GROUP_MEMBER_REQUIRED))
                .when(groupQuestionPermissionService)
                .assertQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", GROUP_ID)));

        assertEquals(QuestionErrorCode.GROUP_MEMBER_REQUIRED.getCode(), exception.getCode());
        // 公开题不能因为 status=1 就跳过小组校验
        verify(groupQuestionPermissionService)
                .assertQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID);
        verify(groupQuestionPermissionService, never()).canAccessPrivateQuestion(any());
        verifyNoInteractions(submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("题目不在小组：透传权限错误，不写任何记录")
    void groupSubmitRejectsQuestionNotInGroup() {
        doThrow(new BizException(QuestionErrorCode.GROUP_QUESTION_NOT_IN_GROUP))
                .when(groupQuestionPermissionService)
                .assertQuestionInGroupAndIsGroupMember(QUESTION_ID, GROUP_ID);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", GROUP_ID)));

        assertEquals(QuestionErrorCode.GROUP_QUESTION_NOT_IN_GROUP.getCode(), exception.getCode());
        verifyNoInteractions(submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("小组提交成功：submit_record + group_submit_record + Outbox 同一事务")
    void groupSubmitWritesIndexAndPublishesOutbox() {
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(902L);
            return 1;
        });
        when(groupSubmitRecordMapper.insert(any(GroupSubmitRecord.class))).thenReturn(1);

        assertEquals(902L, judgeService.judge(request("GROUP", GROUP_ID)));

        ArgumentCaptor<GroupSubmitRecord> captor = ArgumentCaptor.forClass(GroupSubmitRecord.class);
        verify(groupSubmitRecordMapper).insert(captor.capture());
        GroupSubmitRecord index = captor.getValue();
        assertEquals(902L, index.getSubmitRecordId());
        assertEquals(USER_ID, index.getUserId());
        assertEquals(GROUP_ID, index.getGroupId());
        assertNotNull(index.getCreateTime());
        verify(outboxPublisher).publish(EventTypes.JUDGE_SUBMIT_REQUEST, 902L);
    }

    @Test
    @DisplayName("小组索引写入失败：整个事务回滚，且不发布判题事件")
    void groupSubmitRollsBackWhenIndexInsertFails() {
        TransactionStatus status = captureRollbackStatus();
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(903L);
            return 1;
        });
        when(groupSubmitRecordMapper.insert(any(GroupSubmitRecord.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", GROUP_ID)));

        assertEquals(QuestionErrorCode.GROUP_SUBMIT_RECORD_SAVE_FAILED.getCode(), exception.getCode());
        verify(status).setRollbackOnly();
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    @DisplayName("小组题目下架/审核中：即使有权限也不能提交")
    void groupSubmitRejectsOffShelfAndUnderReviewQuestions() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(0));
        BizException offShelf = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", GROUP_ID)));
        assertEquals(QuestionErrorCode.QUESTION_OFF_SHELF.getCode(), offShelf.getCode());

        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(2));
        BizException underReview = assertThrows(BizException.class,
                () -> judgeService.judge(request("GROUP", GROUP_ID)));
        assertEquals(QuestionErrorCode.QUESTION_UNDER_REVIEW.getCode(), underReview.getCode());
        verifyNoInteractions(submitRecordMapper, groupSubmitRecordMapper, outboxPublisher);
    }

    @Test
    @DisplayName("事务回调返回 null：按判题服务不可用兜底")
    void judgeMapsNullTransactionResultToUnavailable() {
        doReturn(null).when(transactionTemplate).execute(any());

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
    }

    @Test
    @DisplayName("事务执行抛非业务异常：回滚并保留 cause")
    void judgeWrapsUnexpectedTransactionFailure() {
        RuntimeException cause = new RuntimeException("db down");
        org.mockito.Mockito.doThrow(cause).when(transactionTemplate).execute(any());

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
        assertEquals(cause, exception.getCause());
    }

    @Test
    void debugPublishesDebugRequestToOutboxWithSameUuid() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        DebugDto debugDto = new DebugDto();
        debugDto.setCode("print(1)");
        debugDto.setLanguage("Python");
        debugDto.setQuestionId(QUESTION_ID);

        String uuid = judgeService.debug(debugDto);

        assertNotNull(uuid);
        // Redis 任务写入先于 Outbox 登记
        verify(hashOperations).put(eq(RedisContext.JUDGE_DEBUG_KEY), eq(uuid), eq(debugDto));
        verify(outboxPublisher).publish(
                eq(EventTypes.JUDGE_DEBUG_REQUEST),
                eq(uuid));
    }

    @Test
    void debugRejectsNullRequest() {
        BizException exception = assertThrows(BizException.class, () -> judgeService.debug(null));
        assertEquals(CommonErrorCode.PARAM_MISSING.getCode(), exception.getCode());
    }

    @Test
    void debugRejectsMissingQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);
        DebugDto debugDto = new DebugDto();
        debugDto.setQuestionId(QUESTION_ID);

        BizException exception = assertThrows(BizException.class, () -> judgeService.debug(debugDto));
        assertEquals(QuestionErrorCode.QUESTION_NOT_FOUND.getCode(), exception.getCode());
        verifyNoInteractions(redisTemplate, outboxPublisher);
    }

    private TransactionStatus captureRollbackStatus() {
        TransactionStatus status = mock(TransactionStatus.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        }).when(transactionTemplate).execute(any());
        return status;
    }

    private static GetCodeDto request(String scene) {
        return request(scene, null);
    }

    private static GetCodeDto request(String scene, Long groupId) {
        GetCodeDto dto = new GetCodeDto();
        dto.setCode("print(1)");
        dto.setLanguage("Python");
        dto.setQuestionId(QUESTION_ID);
        dto.setQuestionTitle("两数之和");
        dto.setSubmitScene(scene);
        dto.setGroupId(groupId);
        return dto;
    }

    private static Question question(int status) {
        return Question.builder()
                .questionId(QUESTION_ID)
                .status(status)
                .createUserId(10L)
                .build();
    }
}

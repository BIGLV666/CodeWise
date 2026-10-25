package org.example.servicequestion.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JudgeService 事务性 Outbox（OutboxPro）单测：
 * 提交/调试不再直接发 MQ，改为在事务内 publish Outbox 事件（路由由 EventRegistry 解析）。
 *
 * <p>新增的题目可见性判断依赖 {@link QuestionMapper} 与
 * {@link GroupQuestionPermissionService}，测试必须为它们提供替身；否则
 * {@code questionMapper} 为 null 会直接 NPE。</p>
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceTest {

    private static final long QUESTION_ID = 5L;

    @Mock
    private SubmitRecordMapper submitRecordMapper;

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
        UserContext.setUserId(42L);
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
    }

    @Test
    void judgeRejectsUnsupportedSubmitScene() {
        GetCodeDto dto = request("UNKNOWN");

        BizException exception = assertThrows(BizException.class, () -> judgeService.judge(dto));
        assertEquals(QuestionErrorCode.SUBMIT_SCENE_UNSUPPORTED.getCode(), exception.getCode());
        // 场景校验先于题目查询/小组 Feign，非法请求不产生任何 IO
        verifyNoInteractions(questionMapper, groupQuestionPermissionService,
                submitRecordMapper, outboxPublisher);
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
                submitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeRejectsMissingQuestion() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(CommonErrorCode.RESOURCE_NOT_FOUND.getCode(), exception.getCode());
        verifyNoInteractions(submitRecordMapper, outboxPublisher);
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
        verifyNoInteractions(submitRecordMapper, outboxPublisher);
    }

    @Test
    void judgeMapsInsertFailureToBusinessErrorAndMarksRollback() {
        TransactionStatus status = mock(TransactionStatus.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        }).when(transactionTemplate).execute(any());
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> judgeService.judge(request("NORMAL")));

        assertEquals(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
        verify(status).setRollbackOnly();
        verifyNoInteractions(outboxPublisher);
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

    private static GetCodeDto request(String scene) {
        GetCodeDto dto = new GetCodeDto();
        dto.setCode("print(1)");
        dto.setLanguage("Python");
        dto.setQuestionId(QUESTION_ID);
        dto.setQuestionTitle("两数之和");
        dto.setSubmitScene(scene);
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

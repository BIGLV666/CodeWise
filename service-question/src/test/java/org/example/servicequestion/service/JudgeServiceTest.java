package org.example.servicequestion.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JudgeService 事务性 Outbox（OutboxPro）单测：
 * 提交/调试不再直接发 MQ，改为在事务内 publish Outbox 事件（路由由 EventRegistry 解析）。
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceTest {

    @Mock
    private SubmitRecordMapper submitRecordMapper;

    @Mock
    private OutboxProPublisher outboxPublisher;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private JudgeService judgeService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(42L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void judgePublishesSubmitRequestToOutboxAfterInsert() {
        when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            // 模拟 MyBatis-Plus 回填自增主键
            invocation.getArgument(0, SubmitRecord.class).setSubmitRecordId(777L);
            return 1;
        });

        GetCodeDto getCodeDto = new GetCodeDto();
        getCodeDto.setCode("print(1)");
        getCodeDto.setLanguage("Python");
        getCodeDto.setQuestionId(5L);
        getCodeDto.setQuestionTitle("两数之和");

        Long submitRecordId = judgeService.judge(getCodeDto);

        assertEquals(777L, submitRecordId);
        verify(submitRecordMapper).insert(any(SubmitRecord.class));

        verify(outboxPublisher).publish(
                eq(EventTypes.JUDGE_SUBMIT_REQUEST),
                eq(777L));
    }

    @Test
    void debugPublishesDebugRequestToOutboxWithSameUuid() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        DebugDto debugDto = new DebugDto();
        debugDto.setCode("print(1)");
        debugDto.setLanguage("Python");

        String uuid = judgeService.debug(debugDto);

        assertNotNull(uuid);
        // Redis 任务写入先于 Outbox 登记
        verify(hashOperations).put(eq(RedisContext.JUDGE_DEBUG_KEY), eq(uuid), eq(debugDto));

        verify(outboxPublisher).publish(
                eq(EventTypes.JUDGE_DEBUG_REQUEST),
                eq(uuid));
    }
}

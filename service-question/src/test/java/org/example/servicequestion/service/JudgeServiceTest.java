package org.example.servicequestion.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.outbox.OutboxService;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JudgeService 事务性 Outbox 改造单测：
 * 提交/调试不再直接发 MQ，改为在事务内登记 Outbox 事件。
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceTest {

    @Mock
    private SubmitRecordMapper submitRecordMapper;

    @Mock
    private OutboxService outboxService;

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
    void judgeAppendsSubmitRequestToOutboxAfterInsert() {
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

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(
                eq(EventTypes.JUDGE_SUBMIT_REQUEST),
                eq(MqContexts.JUDGE_EXCHANGE),
                eq(MqContexts.JUDGE_ROUTING_KEY),
                payloadCaptor.capture());
        assertEquals(777L, payloadCaptor.getValue());
    }

    @Test
    void debugAppendsDebugRequestToOutboxWithSameUuid() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        DebugDto debugDto = new DebugDto();
        debugDto.setCode("print(1)");
        debugDto.setLanguage("Python");

        String uuid = judgeService.debug(debugDto);

        assertNotNull(uuid);
        // Redis 任务写入先于 Outbox 登记
        verify(hashOperations).put(eq(RedisContext.JUDGE_DEBUG_KEY), eq(uuid), eq(debugDto));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(
                eq(EventTypes.JUDGE_DEBUG_REQUEST),
                eq(MqContexts.JUDGE_EXCHANGE),
                eq(MqContexts.JUDGE_DEBUG_ROUTING_KEY),
                payloadCaptor.capture());
        assertEquals(uuid, payloadCaptor.getValue());
    }
}

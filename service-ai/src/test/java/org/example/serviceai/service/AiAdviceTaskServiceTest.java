package org.example.serviceai.service;

import org.example.serviceai.dto.AiTaskChangesDto;
import org.example.serviceai.dto.AiTaskDto;
import org.example.serviceai.dto.AiTaskRootDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAdviceTaskServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long QUESTION_ID = 20L;
    private static final Duration TASK_TTL = Duration.ofMinutes(50);

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ListOperations<String, Object> listOperations;

    @InjectMocks
    private AiAdviceTaskService service;

    @Test
    void addChangesExpiresRealTaskKeysAndSetsChangesTtlOnFirstWrite() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(valueOperations.get(taskRootKey())).thenReturn(null);
        when(listOperations.rightPush(eq(changesKey()), any())).thenReturn(1L);
        when(listOperations.size(changesKey())).thenReturn(1L);

        AiTaskDto task = new AiTaskDto();
        task.setUserId(USER_ID);
        task.setQuestionId(QUESTION_ID);
        task.setCode("print(1)");
        task.setChanges(List.of(new AiTaskChangesDto()));

        service.addChanges(task);

        verify(redisTemplate).expire(taskRootKey(), TASK_TTL);
        verify(redisTemplate, atLeastOnce()).expire(changesKey(), TASK_TTL);
        verify(redisTemplate).expire(adviceKey(), TASK_TTL);
        assertNoDoubleColonExpireKey();
    }

    @Test
    void getAiAdvicesRenewsRealTaskKeys() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(adviceKey())).thenReturn(1L);
        List<Object> storedAdvices = List.of("建议");
        when(listOperations.range(adviceKey(), 0L, 1L)).thenReturn(storedAdvices);

        List<String> advices = service.getAiAdvices(USER_ID, QUESTION_ID);

        assertEquals(List.of("建议"), advices);
        verify(redisTemplate).expire(taskRootKey(), TASK_TTL);
        verify(redisTemplate).expire(changesKey(), TASK_TTL);
        verify(redisTemplate).expire(adviceKey(), TASK_TTL);
        assertNoDoubleColonExpireKey();
    }

    @Test
    void stressAddChangesKeepsRealKeyAndTtlAcrossManyWrites() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        AiTaskRootDto root = new AiTaskRootDto();
        root.setUserId(USER_ID);
        root.setQuestionId(QUESTION_ID);
        root.setCreateTime(LocalDateTime.now().minusMinutes(21));
        root.setLastEvaluatedTime(LocalDateTime.now().minusMinutes(20));
        root.setLastActiveTime(LocalDateTime.now().minusSeconds(10));
        when(valueOperations.get(taskRootKey())).thenReturn(root);

        AiTaskChangesDto change = new AiTaskChangesDto();
        change.setVersion(1);
        change.setCreateTime(LocalDateTime.now().minusSeconds(46));
        when(listOperations.rightPush(eq(changesKey()), any())).thenReturn(1L);
        when(listOperations.size(changesKey())).thenReturn(1L);

        AiTaskDto task = new AiTaskDto();
        task.setUserId(USER_ID);
        task.setQuestionId(QUESTION_ID);
        task.setCode("print(1)");
        task.setChanges(List.of(change));

        for (int i = 0; i < 10_000; i++) {
            assertTrue(service.addChanges(task));
        }

        // 每次写入有首次 TTL 兜底 + 统一续期各一次，因此 changesKey 是两倍。
        verify(redisTemplate, times(20_000)).expire(changesKey(), TASK_TTL);
        verify(redisTemplate, times(10_000)).expire(taskRootKey(), TASK_TTL);
        verify(redisTemplate, times(10_000)).expire(adviceKey(), TASK_TTL);
        assertNoDoubleColonExpireKey();
    }

    private void assertNoDoubleColonExpireKey() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, atLeastOnce()).expire(keyCaptor.capture(), any(Duration.class));
        assertTrue(keyCaptor.getAllValues().stream().noneMatch(key -> key.contains("::")),
                "expire keys: " + keyCaptor.getAllValues());
    }

    private static String taskRootKey() {
        return "ai_task_key:" + USER_ID + ":" + QUESTION_ID;
    }

    private static String changesKey() {
        return "ai_task_changes_key:" + USER_ID + ":" + QUESTION_ID;
    }

    private static String adviceKey() {
        return "ai_advice_key:" + USER_ID + ":" + QUESTION_ID;
    }
}

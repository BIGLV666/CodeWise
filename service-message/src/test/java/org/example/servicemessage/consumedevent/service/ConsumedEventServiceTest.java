package org.example.servicemessage.consumedevent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.servicemessage.consumedevent.entry.ConsumedEvent;
import org.example.servicemessage.consumedevent.entry.ConsumedEventStatus;
import org.example.servicemessage.consumedevent.mapper.ConsumedEventMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsumedEventServiceTest {
    private static final String EVENT_ID = "event-1";
    private static final String ROUTING_KEY = "email.routing";

    @Mock
    private ConsumedEventMapper consumedEventMapper;

    @InjectMocks
    private ConsumedEventService consumedEventService;

    /** 纯单测无 MyBatis-Plus 运行时，需手动初始化实体的表信息以支持 Lambda 条件构造。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConsumedEvent.class);
    }

    @Test
    void claimInsertsProcessingRowAndReturnsNew() {
        when(consumedEventMapper.insert(any(ConsumedEvent.class))).thenReturn(1);

        ConsumedEventService.ClaimResult result = consumedEventService.claim(EVENT_ID, ROUTING_KEY);

        assertEquals(ConsumedEventService.ClaimResult.NEW, result);
        ArgumentCaptor<ConsumedEvent> captor = ArgumentCaptor.forClass(ConsumedEvent.class);
        verify(consumedEventMapper).insert(captor.capture());
        assertEquals(EVENT_ID, captor.getValue().getEventId());
        assertEquals(ROUTING_KEY, captor.getValue().getRoutingKey());
        assertEquals(ConsumedEventStatus.PROCESSING, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getRetryCount());
    }

    @Test
    void claimReturnsDuplicateCompletedWhenRowCompleted() {
        when(consumedEventMapper.insert(any(ConsumedEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(consumedEventMapper.selectOne(any())).thenReturn(buildEvent(ConsumedEventStatus.COMPLETED));

        assertEquals(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED,
                consumedEventService.claim(EVENT_ID, ROUTING_KEY));
    }

    @Test
    void claimReturnsReclaimWhenRowProcessingOrFailed() {
        when(consumedEventMapper.insert(any(ConsumedEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(consumedEventMapper.selectOne(any()))
                .thenReturn(buildEvent(ConsumedEventStatus.PROCESSING))
                .thenReturn(buildEvent(ConsumedEventStatus.FAILED));

        assertEquals(ConsumedEventService.ClaimResult.RECLAIM, consumedEventService.claim(EVENT_ID, ROUTING_KEY));
        assertEquals(ConsumedEventService.ClaimResult.RECLAIM, consumedEventService.claim(EVENT_ID, ROUTING_KEY));
    }

    @Test
    void claimRetriesInsertOnceWhenExistingRowMissing() {
        when(consumedEventMapper.insert(any(ConsumedEvent.class)))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);
        when(consumedEventMapper.selectOne(any())).thenReturn(null);

        assertEquals(ConsumedEventService.ClaimResult.NEW, consumedEventService.claim(EVENT_ID, ROUTING_KEY));
        verify(consumedEventMapper, times(2)).insert(any(ConsumedEvent.class));
    }

    @Test
    void claimThrowsWhenRowMissingAndInsertKeepsConflicting() {
        when(consumedEventMapper.insert(any(ConsumedEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(consumedEventMapper.selectOne(any())).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> consumedEventService.claim(EVENT_ID, ROUTING_KEY));
    }

    @Test
    void completeUpdatesStatusToCompleted() {
        consumedEventService.complete(EVENT_ID);

        Map<String, Object> params = captureUpdateParams();
        assertTrue(params.containsValue(EVENT_ID));
        assertTrue(params.containsValue(ConsumedEventStatus.COMPLETED));
    }

    @Test
    void recordFailureIncrementsRetryCountAtomicallyAndKeepsError() throws Exception {
        // 回读返回自增后的值（update 使用 setSql 原子自增，不再读改写）
        ConsumedEvent afterIncrement = buildEvent(ConsumedEventStatus.PROCESSING);
        afterIncrement.setRetryCount(2);
        when(consumedEventMapper.selectOne(any())).thenReturn(afterIncrement);

        int retryCount = consumedEventService.recordFailure(EVENT_ID, "boom");

        assertEquals(2, retryCount);
        LambdaUpdateWrapper<ConsumedEvent> wrapper = captureUpdateWrapper();
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(EVENT_ID));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("boom"));
        assertTrue(wrapper.getSqlSet().contains("retry_count = retry_count + 1"));
    }

    @Test
    void recordFailureTruncatesLongError() {
        when(consumedEventMapper.selectOne(any()))
                .thenReturn(buildEvent(ConsumedEventStatus.PROCESSING));
        String longError = "x".repeat(600);

        consumedEventService.recordFailure(EVENT_ID, longError);

        Map<String, Object> params = captureUpdateParams();
        assertTrue(params.containsValue("x".repeat(500)));
    }

    @Test
    void recordFailureReturnsZeroWhenRowMissing() {
        when(consumedEventMapper.selectOne(any())).thenReturn(null);

        assertEquals(0, consumedEventService.recordFailure(EVENT_ID, "boom"));
    }

    @Test
    void markFailedUpdatesStatusAndError() {
        consumedEventService.markFailed(EVENT_ID, "boom");

        Map<String, Object> params = captureUpdateParams();
        assertTrue(params.containsValue(EVENT_ID));
        assertTrue(params.containsValue(ConsumedEventStatus.FAILED));
        assertTrue(params.containsValue("boom"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> captureUpdateParams() {
        LambdaUpdateWrapper<ConsumedEvent> wrapper = captureUpdateWrapper();
        // eq 条件值在片段渲染时才写入参数表（set 值构造时已写入），先渲染再断言
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<ConsumedEvent> captureUpdateWrapper() {
        ArgumentCaptor<Wrapper<ConsumedEvent>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(consumedEventMapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<ConsumedEvent>) captor.getValue();
    }

    private ConsumedEvent buildEvent(ConsumedEventStatus status) {
        return ConsumedEvent.builder()
                .eventId(EVENT_ID)
                .status(status)
                .retryCount(0)
                .build();
    }
}

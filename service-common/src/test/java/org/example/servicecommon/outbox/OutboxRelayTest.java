package org.example.servicecommon.outbox;

import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicecommon.outbox.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Outbox 中转投递器单元测试：覆盖成功投递、失败退避、超限转 DEAD 三条路径。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxMapper outboxMapper;

    @Mock
    private EventPublisher eventPublisher;

    private OutboxEvent buildPendingEvent(int retryCount) {
        EventEnvelope envelope = EnvelopeCodec.wrap("JUDGE_SUBMIT_REQUEST", "service-question", null, 1L);
        return OutboxEvent.builder()
                .outboxId(100L)
                .eventId(envelope.getEventId())
                .eventType("JUDGE_SUBMIT_REQUEST")
                .exchangeName("judge.exchange")
                .routingKey("judge.routing")
                .payload(EnvelopeCodec.serialize(envelope))
                .producer("service-question")
                .status(OutboxEvent.STATUS_PENDING)
                .retryCount(retryCount)
                .build();
    }

    @Test
    void 投递成功应标记SENT并写投递时间() {
        OutboxEvent event = buildPendingEvent(0);
        when(outboxMapper.claimPendingBatch(anyInt())).thenReturn(List.of(event));
        when(outboxMapper.updateById(any(OutboxEvent.class))).thenReturn(1);

        new OutboxRelay(outboxMapper, eventPublisher).relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).updateById(captor.capture());
        assertEquals(OutboxEvent.STATUS_SENT, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getSentTime());
        verify(eventPublisher).publishRawEnvelope(
                any(String.class), any(String.class), any(EventEnvelope.class), any(String.class), anyInt());
    }

    @Test
    void 投递失败应指数退避并推进重试计数() {
        OutboxEvent event = buildPendingEvent(0);
        when(outboxMapper.claimPendingBatch(anyInt())).thenReturn(List.of(event));
        when(outboxMapper.updateById(any(OutboxEvent.class))).thenReturn(1);
        doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher)
                .publishRawEnvelope(any(String.class), any(String.class), any(EventEnvelope.class),
                        any(String.class), anyInt());

        new OutboxRelay(outboxMapper, eventPublisher).relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).updateById(captor.capture());
        OutboxEvent updated = captor.getValue();
        assertEquals(OutboxEvent.STATUS_PENDING, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNotNull(updated.getNextRetryTime());
        assertTrue(updated.getNextRetryTime().isAfter(LocalDateTime.now()));
        assertTrue(updated.getLastError().contains("broker down"));
    }

    @Test
    void 超过最大重试应转DEAD() {
        OutboxEvent event = buildPendingEvent(7);
        when(outboxMapper.claimPendingBatch(anyInt())).thenReturn(List.of(event));
        when(outboxMapper.updateById(any(OutboxEvent.class))).thenReturn(1);
        doThrow(new IllegalStateException("broker down"))
                .when(eventPublisher)
                .publishRawEnvelope(any(String.class), any(String.class), any(EventEnvelope.class),
                        any(String.class), anyInt());

        new OutboxRelay(outboxMapper, eventPublisher).relay();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).updateById(captor.capture());
        OutboxEvent updated = captor.getValue();
        assertEquals(OutboxEvent.STATUS_DEAD, updated.getStatus());
        assertEquals(8, updated.getRetryCount());
        assertNull(updated.getNextRetryTime());
    }

    @Test
    void 无到期事件不应触发任何投递() {
        when(outboxMapper.claimPendingBatch(anyInt())).thenReturn(List.of());

        new OutboxRelay(outboxMapper, eventPublisher).relay();

        verify(outboxMapper, never()).updateById(any(OutboxEvent.class));
        verify(eventPublisher, never()).publishRawEnvelope(
                any(String.class), any(String.class), any(EventEnvelope.class), any(String.class), anyInt());
    }

    @Test
    void 单条失败不应阻断同批其他事件() {
        OutboxEvent failed = buildPendingEvent(0);
        failed.setOutboxId(1L);
        OutboxEvent success = buildPendingEvent(0);
        success.setOutboxId(2L);
        when(outboxMapper.claimPendingBatch(anyInt())).thenReturn(List.of(failed, success));
        when(outboxMapper.updateById(any(OutboxEvent.class))).thenReturn(1);
        doThrow(new IllegalStateException("boom"))
                .when(eventPublisher)
                .publishRawEnvelope(any(String.class), any(String.class), any(EventEnvelope.class),
                        any(String.class), anyInt());

        new OutboxRelay(outboxMapper, eventPublisher).relay();

        verify(outboxMapper, times(2)).updateById(any(OutboxEvent.class));
    }
}

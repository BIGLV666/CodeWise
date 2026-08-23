package org.example.servicereview.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.outbox.OutboxService;
import org.example.servicereview.dto.ReviewReminderDto;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复习提醒任务测试：提醒经事务性 Outbox 登记（REVIEW_REMINDER），messageId 按天幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewMessageTaskTest {

    private static final Long USER_ID = 7L;

    @Mock
    private OutboxService outboxService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private ReviewMessageTask reviewMessageTask;

    @BeforeEach
    void stubCommonFlow() {
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void morningTaskPublishesNotRecordReminderViaOutbox() {
        when(reviewMapper.getNotRecord()).thenReturn(List.of(buildReminder(USER_ID)));

        reviewMessageTask.NotRecordMessageTask();

        NotificationDto payload = captureAppendPayload();
        assertEquals(EventTypes.REVIEW_REMINDER, captureAppendEventType());
        assertEquals("review-reminder:not_record:" + USER_ID + ":" + LocalDate.now(),
                payload.getMessageId());
    }

    @Test
    void nightTaskPublishesHaveRecordReminderViaOutbox() {
        when(reviewRecordMapper.getHaveRecord()).thenReturn(List.of(buildReminder(USER_ID)));

        reviewMessageTask.haveRecordMessageTask();

        NotificationDto payload = captureAppendPayload();
        assertEquals(EventTypes.REVIEW_REMINDER, captureAppendEventType());
        assertEquals("review-reminder:have_record:" + USER_ID + ":" + LocalDate.now(),
                payload.getMessageId());
    }

    private String captureAppendEventType() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).append(captor.capture(), anyString(), anyString(), any());
        return captor.getValue();
    }

    private NotificationDto captureAppendPayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).append(anyString(), eq(MqContexts.NOTIFICATION_EXCHANGE),
                eq(MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY), captor.capture());
        return (NotificationDto) captor.getValue();
    }

    private ReviewReminderDto buildReminder(Long userId) {
        ReviewReminderDto reminder = new ReviewReminderDto();
        reminder.setUserId(userId);
        reminder.setPendingCount(3L);
        return reminder;
    }
}

package org.example.servicereview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.ReviewMasteredDto;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.entry.ReviewRecord;
import org.example.servicereview.mapper.ReviewConfigMapper;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.outboxpro.core.OutboxProPublisher;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复习掌握祝贺生产端测试：SM-2 更新使复习计划首次由 学习中(0) 转为 已掌握(1) 时，
 * 经 Outbox 同事务登记 REVIEW_MASTERED 事件（路由键、payload 字段）；未达掌握阈值
 * 或此前已掌握则不发布。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceMasteredNotificationTest {

    private static final Long USER_ID = 7L;
    private static final Long QUESTION_ID = 101L;
    private static final Long JUDGE_RECORD_ID = 9L;
    private static final String EVENT_ID = "review:judge:" + JUDGE_RECORD_ID;
    private static final long DELIVERY_TAG = 1L;
    private static final LocalDateTime JOIN_TIME = LocalDateTime.of(2026, 1, 15, 9, 30, 0);

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private ReviewConfigMapper reviewConfigMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;
    @Mock
    private QuestionFeignClient questionFeignClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private Channel channel;
    @Mock
    private ConsumedEventService consumedEventService;
    @Mock
    private OutboxProPublisher outboxPublisher;

    @InjectMocks
    private ReviewService reviewService;

    @BeforeEach
    void stubCommonFlow() throws InterruptedException {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(reviewConfigMapper.selectOne(any())).thenReturn(ReviewConfig.builder()
                .userId(USER_ID)
                .enableAutoReview(1)
                .countCompileError(1)
                .reviewCount(Integer.MAX_VALUE)
                .minEasinessFactor(new BigDecimal("1.30"))
                .initialEasinessFactor(new BigDecimal("2.50"))
                .masteredIntervalDays(10)
                .build());
        when(consumedEventService.claim(anyString(), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);
        when(reviewRecordMapper.getTodayRecord(USER_ID)).thenReturn(ReviewRecord.builder()
                .reviewRecordId(1L)
                .userId(USER_ID)
                .pendingReviewQuestionIds(new ArrayList<>(List.of(QUESTION_ID)))
                .completedReviewQuestionIds(new ArrayList<>())
                .acQuestionIds(new ArrayList<>())
                .reviewDate(LocalDate.now())
                .build());
        when(reviewMapper.updateById(any(Review.class))).thenReturn(1);
        when(reviewRecordMapper.updateById(any(ReviewRecord.class))).thenReturn(1);
    }

    @Test
    void masteredTransitionPublishesOutboxEventInSameTransaction() throws Exception {
        // repetitions=2 且 intervalDays=6：本次 AC 后 interval = 6 * 2.60 = 15.6 -> 16，达到阈值 10 转掌握
        stubReview(buildReview(2, 6, 5, 0));

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        ArgumentCaptor<ReviewMasteredDto> payloadCaptor = ArgumentCaptor.forClass(ReviewMasteredDto.class);
        verify(outboxPublisher).publish(eq(EventTypes.REVIEW_MASTERED), payloadCaptor.capture());
        ReviewMasteredDto payload = payloadCaptor.getValue();
        assertEquals("review:mastered:" + USER_ID + ":" + QUESTION_ID, payload.getMessageId());
        assertEquals(USER_ID, payload.getUserId());
        assertEquals(QUESTION_ID, payload.getQuestionId());
        // 题目名由消费端 Feign 补齐，生产端不填
        assertNull(payload.getQuestionTitle());
        assertEquals("2026-01-15 09:30:00", payload.getJoinTime());
        assertEquals(6, payload.getTotalReviewCount());
        assertTrue(payload.getMasteredTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "masteredTime 应为 yyyy-MM-dd HH:mm:ss 格式");
        // 业务链路不受影响：SM-2 更新、快照更新、事件 complete 与 ACK 均正常
        verify(reviewMapper).updateById(any(Review.class));
        verify(consumedEventService).complete(EVENT_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void belowMasteredThresholdDoesNotPublish() throws Exception {
        // repetitions=1 且 intervalDays=1：本次 AC 后 interval = 6，未达阈值 10，状态仍为 0
        stubReview(buildReview(1, 1, 5, 0));

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(outboxPublisher, never()).publish(anyString(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void alreadyMasteredDoesNotPublishAgain() throws Exception {
        // 此前已掌握（status=1，calculate 只会维持 1）：非 0→1 首次流转，不重复发布
        stubReview(buildReview(2, 6, 5, 1));

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(outboxPublisher, never()).publish(anyString(), any());
    }

    private void stubReview(Review review) {
        when(reviewMapper.selectOne(any())).thenReturn(review);
    }

    /** 构造 SM-2 计算前的复习记录；reviewCount/status 对应各自用例的起始状态。 */
    private Review buildReview(Integer repetitions, Integer intervalDays, Integer reviewCount,
                               Integer status) {
        return Review.builder()
                .reviewId(1L)
                .userId(USER_ID)
                .questionId(QUESTION_ID)
                .easinessFactor(new BigDecimal("2.5"))
                .repetitions(repetitions)
                .intervalDays(intervalDays)
                .reviewCount(reviewCount)
                .status(status)
                .createTime(JOIN_TIME)
                .build();
    }

    private ReviewJudgeRecordDto buildDto() {
        ReviewJudgeRecordDto dto = new ReviewJudgeRecordDto();
        dto.setUserId(USER_ID);
        dto.setQuestionId(QUESTION_ID);
        dto.setJudgeRecordId(JUDGE_RECORD_ID);
        dto.setSubmitRecordId(11L);
        dto.setStatus("AC");
        dto.setAcTestTotal(5);
        dto.setAllTestTotal(5);
        return dto;
    }

    private String bareBody() {
        try {
            return json.writeValueAsString(buildDto());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Message amqpMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

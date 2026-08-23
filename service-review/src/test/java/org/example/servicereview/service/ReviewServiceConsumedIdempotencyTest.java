package org.example.servicereview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.entry.ReviewRecord;
import org.example.servicereview.mapper.ReviewConfigMapper;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * setReview 消费幂等行为测试：事件级幂等（consumed_event claim/complete 与业务同事务）、
 * 信封/裸格式双读、毒消息（judgeRecordId 缺失）处置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewServiceConsumedIdempotencyTest {

    private static final Long USER_ID = 7L;
    private static final Long QUESTION_ID = 101L;
    private static final Long JUDGE_RECORD_ID = 9L;
    private static final String EVENT_ID = "review:judge:" + JUDGE_RECORD_ID;
    private static final long DELIVERY_TAG = 1L;

    private final ObjectMapper json = new ObjectMapper();

    @Mock
    private ReviewConfigMapper reviewConfigMapper;
    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private ReviewRecordMapper reviewRecordMapper;
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

    @InjectMocks
    private ReviewService reviewService;

    @BeforeEach
    void stubCommonFlow() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void duplicateCompletedEventSkipsBusinessAndAcks() throws Exception {
        stubLockAndRedis();
        when(reviewConfigMapper.selectOne(any())).thenReturn(buildConfig());
        when(consumedEventService.claim(EVENT_ID, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY))
                .thenReturn(ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED);

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(reviewMapper, never()).updateById(any(Review.class));
        verify(reviewRecordMapper, never()).updateById(any(ReviewRecord.class));
        verify(consumedEventService, never()).complete(anyString());
        verify(hashOperations).delete(eq(RedisContext.REVIEW_JUDGE_RETRY_COUNT_KEY), anyString());
    }

    @Test
    void reclaimEventExecutesBusinessExactlyOnceAndCompletes() throws Exception {
        stubLockAndRedis();
        stubBusinessHappyPath();
        when(consumedEventService.claim(EVENT_ID, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY))
                .thenReturn(ConsumedEventService.ClaimResult.RECLAIM);

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(reviewMapper).updateById(any(Review.class));
        verify(reviewRecordMapper).updateById(any(ReviewRecord.class));
        verify(consumedEventService).complete(EVENT_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void envelopeFormatIsUnwrappedAndProcessed() throws Exception {
        stubLockAndRedis();
        stubBusinessHappyPath();
        when(consumedEventService.claim(anyString(), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);

        reviewService.setReview(amqpMessage(envelopeBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(consumedEventService).claim(EVENT_ID, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);
        verify(consumedEventService).complete(EVENT_ID);
        verify(reviewMapper).updateById(any(Review.class));
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void bareFormatStillProcessed() throws Exception {
        stubLockAndRedis();
        stubBusinessHappyPath();
        when(consumedEventService.claim(anyString(), anyString()))
                .thenReturn(ConsumedEventService.ClaimResult.NEW);

        reviewService.setReview(amqpMessage(bareBody()), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(consumedEventService).claim(EVENT_ID, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);
        verify(consumedEventService).complete(EVENT_ID);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void missingJudgeRecordIdTreatedAsPoisonAndNacked() throws Exception {
        ReviewJudgeRecordDto dto = buildDto();
        dto.setJudgeRecordId(null);

        reviewService.setReview(amqpMessage(json.writeValueAsString(dto)), channel,
                MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verifyNoInteractions(consumedEventService);
        verify(transactionTemplate, never()).execute(any());
    }

    private void stubLockAndRedis() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3L, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private void stubBusinessHappyPath() {
        when(reviewConfigMapper.selectOne(any())).thenReturn(buildConfig());
        ReviewRecord todayRecord = ReviewRecord.builder()
                .reviewRecordId(1L)
                .userId(USER_ID)
                .pendingReviewQuestionIds(new java.util.ArrayList<>(List.of(QUESTION_ID)))
                .completedReviewQuestionIds(new java.util.ArrayList<>())
                .acQuestionIds(new java.util.ArrayList<>())
                .reviewDate(LocalDate.now())
                .build();
        when(reviewRecordMapper.getTodayRecord(USER_ID)).thenReturn(todayRecord);
        when(reviewMapper.selectOne(any())).thenReturn(Review.builder()
                .reviewId(1L)
                .userId(USER_ID)
                .questionId(QUESTION_ID)
                .easinessFactor(new BigDecimal("2.5"))
                .repetitions(0)
                .intervalDays(0)
                .reviewCount(0)
                .status(0)
                .build());
        when(reviewMapper.updateById(any(Review.class))).thenReturn(1);
        when(reviewRecordMapper.updateById(any(ReviewRecord.class))).thenReturn(1);
    }

    private ReviewConfig buildConfig() {
        return ReviewConfig.builder()
                .userId(USER_ID)
                .enableAutoReview(1)
                .countCompileError(1)
                .reviewCount(Integer.MAX_VALUE)
                .minEasinessFactor(new BigDecimal("1.30"))
                .initialEasinessFactor(new BigDecimal("2.50"))
                .masteredIntervalDays(30)
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

    private String envelopeBody() {
        EventEnvelope envelope = EnvelopeCodec.wrap("REVIEW_JUDGE_RECORD", "service-question", null, buildDto());
        return EnvelopeCodec.serialize(envelope);
    }

    private Message amqpMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}

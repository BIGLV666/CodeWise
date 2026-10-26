package org.example.servicequestion.service;

import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 判题提交服务层压测：并发下事务回调、主键回填、小组索引与 Outbox 发布不能串号。
 *
 * <p>用 32 线程 × 500 次（普通/小组各半）验证：提交记录 id 全局唯一、每条记录恰好
 * 发布一次判题事件、小组索引数量与小组提交数一致、无异常。该测试是内存 Mock 基准，
 * 用于发现并发逻辑错误，不等价于数据库/MQ 的真实吞吐。</p>
 */
@ExtendWith(MockitoExtension.class)
class JudgeServiceConcurrencyTest {

    private static final int THREADS = 32;
    private static final int PER_THREAD = 500;
    private static final int TOTAL = THREADS * PER_THREAD;
    private static final long QUESTION_ID = 5L;
    private static final long GROUP_ID = 88L;

    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private GroupSubmitRecordMapper groupSubmitRecordMapper;
    @Mock
    private OutboxProPublisher outboxPublisher;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private GroupQuestionPermissionService groupQuestionPermissionService;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private JudgeService judgeService;

    private final AtomicLong idGenerator = new AtomicLong(10_000L);
    private final AtomicLong submitInsertCount = new AtomicLong();
    private final AtomicLong groupInsertCount = new AtomicLong();
    private final AtomicLong outboxPublishCount = new AtomicLong();

    @BeforeEach
    void setUp() {
        UserContext.setUserId(42L);
        lenient().when(questionMapper.selectById(QUESTION_ID)).thenReturn(Question.builder()
                .questionId(QUESTION_ID)
                .status(1)
                .createUserId(10L)
                .build());
        lenient().when(submitRecordMapper.insert(any(SubmitRecord.class))).thenAnswer(invocation -> {
            SubmitRecord record = invocation.getArgument(0, SubmitRecord.class);
            record.setSubmitRecordId(idGenerator.incrementAndGet());
            submitInsertCount.incrementAndGet();
            return 1;
        });
        lenient().when(groupSubmitRecordMapper.insert(any(GroupSubmitRecord.class))).thenAnswer(invocation -> {
            groupInsertCount.incrementAndGet();
            return 1;
        });
        lenient().doAnswer(invocation -> {
            outboxPublishCount.incrementAndGet();
            return null;
        }).when(outboxPublisher).publish(anyString(), any());

        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @Timeout(120)
    @DisplayName("并发压测：32×500 次提交 id 唯一、Outbox 不重不漏、小组索引数正确")
    void concurrentSubmissionsAreIsolated() throws Exception {
        Set<Long> submitRecordIds = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        CountDownLatch startGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>(THREADS);
        long begin = System.nanoTime();
        for (int thread = 0; thread < THREADS; thread++) {
            final int threadIndex = thread;
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        boolean groupSubmit = ((threadIndex + i) & 1) == 0;
                        long callBegin = System.nanoTime();
                        Long submitRecordId = judgeService.judge(groupSubmit
                                ? request("GROUP", GROUP_ID)
                                : request("NORMAL", null));
                        latenciesNanos.add(System.nanoTime() - callBegin);
                        submitRecordIds.add(submitRecordId);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        startGate.countDown();

        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long elapsedNanos = System.nanoTime() - begin;

        assertThat(errors).as("并发提交不应抛异常").isEmpty();
        assertThat(submitRecordIds).as("提交记录 id 必须全局唯一").hasSize(TOTAL);
        assertThat(submitInsertCount.get()).as("每条提交只落库一次").isEqualTo(TOTAL);
        assertThat(outboxPublishCount.get()).as("每条提交恰好发布一次判题事件").isEqualTo(TOTAL);
        int expectedGroupSubmissions = TOTAL / 2;
        assertThat(groupInsertCount.get()).as("小组索引数量应与小组提交数一致")
                .isEqualTo(expectedGroupSubmissions);

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        sorted.sort(Long::compareTo);
        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        double opsPerSecond = TOTAL / (elapsedNanos / 1_000_000_000.0);
        System.out.printf(
                "[stress] total=%d threads=%d elapsed=%dms ops/s=%.0f p50=%.2fms p95=%.2fms p99=%.2fms%n",
                TOTAL, THREADS, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), opsPerSecond,
                p50 / 1_000_000.0, p95 / 1_000_000.0, p99 / 1_000_000.0);

        // 内存 Mock 基准：只防并发逻辑退化，阈值放宽避免 CI 抖动误报
        assertThat(p99).as("p99 单次提交耗时").isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
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
}

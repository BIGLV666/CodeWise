package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.vo.MiniGroupSubmitRecordVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 小组提交记录分页服务层压测：并发请求下分页、批量提交记录与用户组装不能串页。
 *
 * <p>32 线程 × 200 次，共 6400 页；每页固定 20 条。验证无异常、每页条数稳定、
 * 批量查询次数与请求数一致。该测试是内存 Mock 基准，不等价于真实 DB/服务吞吐。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubmitRecordServiceConcurrencyTest {

    private static final int THREADS = 32;
    private static final int PER_THREAD = 200;
    private static final int TOTAL = THREADS * PER_THREAD;
    private static final long GROUP_ID = 88L;

    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private GroupSubmitRecordMapper groupSubmitRecordMapper;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private GroupQuestionPermissionService groupQuestionPermissionService;

    @InjectMocks
    private SubmitRecordService submitRecordService;

    private final AtomicLong submitBatchCalls = new AtomicLong();
    private final AtomicLong userBatchCalls = new AtomicLong();

    @BeforeEach
    void setUp() {
        UserContext.setUserId(42L);
        List<GroupSubmitRecord> rows = new ArrayList<>();
        List<SubmitRecord> submits = new ArrayList<>();
        Map<Long, UserDto> users = new HashMap<>();
        // 21 条 → pageSize 默认 20 时 hasNext=true
        for (long i = 0; i < 21; i++) {
            long groupRecordId = 1000L - i;
            long submitRecordId = 2000L - i;
            long userId = 100L + (i % 3);
            rows.add(GroupSubmitRecord.builder()
                    .groupSubmitRecordId(groupRecordId)
                    .submitRecordId(submitRecordId)
                    .groupId(GROUP_ID)
                    .userId(userId)
                    .createTime(LocalDateTime.now())
                    .build());
            SubmitRecord submit = SubmitRecord.builder()
                    .submitRecordId(submitRecordId)
                    .questionId(7L)
                    .questionTitle("两数之和")
                    .userId(userId)
                    .build();
            submit.setJudgeStatus("AC");
            submits.add(submit);
            UserDto user = new UserDto();
            user.setUserId(String.valueOf(userId));
            user.setNickName("nick" + userId);
            user.setAvatarUrl("avatar" + userId + ".png");
            users.put(userId, user);
        }

        lenient().when(groupSubmitRecordMapper.selectList(any())).thenReturn(rows);
        lenient().when(submitRecordMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            submitBatchCalls.incrementAndGet();
            return submits;
        });
        lenient().when(userFeignClient.getUserList(any())).thenAnswer(invocation -> {
            userBatchCalls.incrementAndGet();
            return Result.success(users);
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @Timeout(120)
    @DisplayName("并发压测：6400 页无异常，每页 20 条，批量查询不丢失")
    void concurrentPaginationIsIsolated() throws Exception {
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        CountDownLatch startGate = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>(THREADS);
        long begin = System.nanoTime();
        for (int thread = 0; thread < THREADS; thread++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        long callBegin = System.nanoTime();
                        CursorPageResult<MiniGroupSubmitRecordVo> page =
                                submitRecordService.cursorMiniGroupSubmitRecord(GROUP_ID, null, 20);
                        latenciesNanos.add(System.nanoTime() - callBegin);
                        assertThat(page.getRecords()).hasSize(20);
                        assertThat(page.getHasNext()).isTrue();
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

        assertThat(errors).as("并发分页不应抛异常").isEmpty();
        assertThat(submitBatchCalls.get()).as("每页一次提交记录批量查询").isEqualTo(TOTAL);
        assertThat(userBatchCalls.get()).as("每页一次用户批量查询").isEqualTo(TOTAL);

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        sorted.sort(Long::compareTo);
        double opsPerSecond = TOTAL / (elapsedNanos / 1_000_000_000.0);
        System.out.printf(
                "[stress] groupSubmitPage total=%d threads=%d elapsed=%dms ops/s=%.0f p50=%.2fms p95=%.2fms p99=%.2fms%n",
                TOTAL, THREADS, TimeUnit.NANOSECONDS.toMillis(elapsedNanos), opsPerSecond,
                percentile(sorted, 50) / 1_000_000.0,
                percentile(sorted, 95) / 1_000_000.0,
                percentile(sorted, 99) / 1_000_000.0);

        assertThat(percentile(sorted, 99))
                .as("p99 单页耗时")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}

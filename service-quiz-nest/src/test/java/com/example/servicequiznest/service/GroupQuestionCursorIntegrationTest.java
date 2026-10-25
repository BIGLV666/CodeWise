package com.example.servicequiznest.service;

import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.example.serviceapi.dto.CursorPageResult;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import com.example.servicequiznest.vo.MinimalQuestionVo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * {@link GroupQuestionService#cursorQuestion} 的真 MySQL 集成测试。
 *
 * <p>题目服务用 {@code @MockBean} 替身（本服务只依赖它的批量瘦身接口），
 * 重点验证真库上的游标翻页顺序、懒删除落库与并发翻页不重不漏。</p>
 */
class GroupQuestionCursorIntegrationTest extends AbstractGroupIntegrationTest {

    private static final long GROUP_ID = 88L;

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
    }

    @MockBean
    private QuestionFeignClient questionFeignClient;

    @Autowired
    private GroupQuestionService groupQuestionService;

    @BeforeEach
    void resetQuestions() {
        jdbcTemplate.execute("delete from group_question");
        UserContext.setUserId(ROOT);
        insertGroup(GROUP_ID, ROOT, 1);
        insertMember(ROOT, GROUP_ID, "ROOT", "APPROVED");
        when(questionFeignClient.getFavoritesBrief(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            List<QuestionBriefDto> briefs = new ArrayList<>();
            for (Long id : ids) {
                briefs.add(QuestionBriefDto.builder().questionId(id).title("题" + id).status(3).build());
            }
            return Result.success(briefs);
        });
    }

    @Test
    @DisplayName("真库翻页：按索引主键升序，三页不重不漏，末页 hasNext=false")
    void walksAllPagesWithoutDuplicates() {
        List<Long> questionIds = new ArrayList<>();
        for (long questionId = 10; questionId < 15; questionId++) {
            questionIds.add(questionId);
            insertQuestion(questionId, 0);
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        boolean hasNext = true;
        while (hasNext) {
            CursorPageResult<MinimalQuestionVo> page =
                    groupQuestionService.cursorQuestion(GROUP_ID, cursor, 2);
            page.getRecords().forEach(record -> seen.add(record.dto().getQuestionId()));
            cursor = page.getNextCursor();
            hasNext = Boolean.TRUE.equals(page.getHasNext());
            pages++;
            assertThat(pages).isLessThanOrEqualTo(3);
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(questionIds);
        assertThat(new HashSet<>(seen)).hasSize(questionIds.size());
        assertThat(rowCountOfGroup()).isEqualTo(5L);
    }

    @Test
    @DisplayName("懒删除：题目服务缺失的索引在真库被清理，其余记录照常返回")
    void staleIndexIsDeletedFromRealDatabase() {
        insertQuestion(20L, 0);
        insertQuestion(21L, 1);
        insertQuestion(22L, 0);
        when(questionFeignClient.getFavoritesBrief(anyList())).thenReturn(Result.success(List.of(
                QuestionBriefDto.builder().questionId(20L).status(3).build(),
                QuestionBriefDto.builder().questionId(22L).status(3).build())));

        CursorPageResult<MinimalQuestionVo> page =
                groupQuestionService.cursorQuestion(GROUP_ID, null, 20);

        assertThat(page.getRecords()).extracting(MinimalQuestionVo::groupQuestionId)
                .containsExactly(questionIdOf(20L), questionIdOf(22L));
        assertThat(rowCountOfGroup()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from group_question where group_id = ? and question_id = 21",
                Long.class, GROUP_ID)).isZero();
    }

    @Test
    @DisplayName("非成员：拒绝访问且不修改题库")
    void nonMemberCannotRead() {
        insertQuestion(30L, 0);
        UserContext.setUserId(MEMBER2);

        assertThatThrownBy(() -> groupQuestionService.cursorQuestion(GROUP_ID, null, 20))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP.getCode());

        assertThat(rowCountOfGroup()).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发翻页：8 个线程各自走完全部页，结果一致且无重复")
    void concurrentPageWalksAreConsistent() throws Exception {
        for (long questionId = 100; questionId < 120; questionId++) {
            insertQuestion(questionId, questionId % 2 == 0 ? 0 : 1);
        }

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        List<Future<List<Long>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < readers; i++) {
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(ROOT);
                    try {
                        List<Long> seen = new ArrayList<>();
                        Long cursor = null;
                        boolean hasNext = true;
                        while (hasNext) {
                            CursorPageResult<MinimalQuestionVo> page =
                                    groupQuestionService.cursorQuestion(GROUP_ID, cursor, 3);
                            page.getRecords().forEach(record -> seen.add(record.dto().getQuestionId()));
                            cursor = page.getNextCursor();
                            hasNext = Boolean.TRUE.equals(page.getHasNext());
                        }
                        return seen;
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            for (Future<List<Long>> future : futures) {
                List<Long> seen = future.get(30, TimeUnit.SECONDS);
                assertThat(seen).hasSize(20);
                assertThat(new HashSet<>(seen)).hasSize(20);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowCountOfGroup()).isEqualTo(20L);
    }

    @Test
    @DisplayName("压测：16 线程 × 50 次混合翻页，错误率 0 且 P95 在阈值内")
    void loadTestMixedPages() throws Exception {
        for (long questionId = 200; questionId < 400; questionId++) {
            insertQuestion(questionId, questionId % 2 == 0 ? 0 : 1);
        }

        // 预热：避免把 JIT/连接池冷启动算进 P95
        for (int i = 0; i < 50; i++) {
            groupQuestionService.cursorQuestion(GROUP_ID, null, 10);
        }

        int threads = 16;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> latenciesNanos = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        long start = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(ROOT);
                    try {
                        for (int i = 0; i < iterations; i++) {
                            Long cursor = i % 4 == 0 ? null : (long) (1 + (i * 7) % 200);
                            long begin = System.nanoTime();
                            try {
                                groupQuestionService.cursorQuestion(GROUP_ID, cursor, 10);
                            } catch (RuntimeException e) {
                                errors.incrementAndGet();
                            } finally {
                                latenciesNanos.add(System.nanoTime() - begin);
                            }
                        }
                    } finally {
                        UserContext.clear();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long elapsedNanos = System.nanoTime() - start;

        List<Long> sorted = new ArrayList<>(latenciesNanos);
        java.util.Collections.sort(sorted);
        long total = sorted.size();
        double qps = total / (elapsedNanos / 1_000_000_000.0);
        long p50 = sorted.get((int) (total * 0.50));
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) (total * 0.95)));
        long p99 = sorted.get(Math.min(sorted.size() - 1, (int) (total * 0.99)));
        System.out.printf("[LOAD] requests=%d threads=%d qps=%.1f p50=%.2fms p95=%.2fms p99=%.2fms errors=%d%n",
                total, threads, qps, p50 / 1_000_000.0, p95 / 1_000_000.0, p99 / 1_000_000.0, errors.get());

        assertThat(errors.get()).as("压测错误数").isZero();
        assertThat(total).isEqualTo((long) threads * iterations);
        assertThat(p95 / 1_000_000.0).as("P95 延迟（毫秒）").isLessThan(500.0);
        assertThat(rowCountOfGroup()).isEqualTo(200L);
    }

    private void insertQuestion(long questionId, int isPlatform) {
        jdbcTemplate.update("insert into group_question(question_id, create_user_id, group_id, is_plat_from_question) "
                + "values(?, ?, ?, ?)", questionId, ROOT, GROUP_ID, isPlatform);
    }

    private long questionIdOf(long questionId) {
        Long mappingId = jdbcTemplate.queryForObject(
                "select group_question_id from group_question where group_id = ? and question_id = ?",
                Long.class, GROUP_ID, questionId);
        return mappingId == null ? -1L : mappingId;
    }

    private long rowCountOfGroup() {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from group_question where group_id = ?", Long.class, GROUP_ID);
        return n == null ? 0 : n;
    }
}

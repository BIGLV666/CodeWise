package com.example.servicequiznest.service;

import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import com.example.servicequiznest.vo.GroupQuestionVo;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小组题目增删改查的真 MySQL 集成测试。
 *
 * <p>题目服务用 {@code @MockBean} 替身，重点验证：本地索引的平台/自建分支、
 * 跨组隔离、平台题禁止修改，以及混合读写压测下的稳定性。</p>
 */
class GroupQuestionCrudIntegrationTest extends AbstractGroupIntegrationTest {

    private static final long GROUP_ID = 88L;
    private static final long OTHER_GROUP_ID = 99L;
    private static final long PLATFORM_QUESTION_ID = 7L;
    private static final long CUSTOM_QUESTION_ID = 500L;

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
        insertGroup(OTHER_GROUP_ID, ADMIN, 1);
        insertMember(ADMIN, OTHER_GROUP_ID, "ROOT", "APPROVED");

        when(questionFeignClient.getQuestionInfo(PLATFORM_QUESTION_ID))
                .thenReturn(Result.success(QuestionDto.builder()
                        .questionId(PLATFORM_QUESTION_ID).status(1).createUserId(ADMIN).build()));
        when(questionFeignClient.addQuestion(any(), any(QuestionDto.class)))
                .thenReturn(Result.success(QuestionDto.builder().questionId(CUSTOM_QUESTION_ID).build()));
        when(questionFeignClient.putQuestion(any(),
                any(org.example.serviceapi.dto.question.UpdateGroupQuestionDto.class)))
                .thenReturn(Result.success(QuestionDto.builder().questionId(CUSTOM_QUESTION_ID).build()));
        when(questionFeignClient.deleteQuestion(any(), anyLong())).thenReturn(Result.success("success"));
    }

    @Test
    @DisplayName("新增平台题：只写索引且标记 is_plat=1")
    void addPlatformQuestionWritesIndex() {
        groupQuestionService.addQuestion(CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID).questionId(PLATFORM_QUESTION_ID).build());

        assertThat(rowCountOfGroup()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select is_plat_from_question from group_question where group_id = ? and question_id = ?",
                Integer.class, GROUP_ID, PLATFORM_QUESTION_ID)).isEqualTo(1);
        verify(questionFeignClient, never()).addQuestion(any(), any(QuestionDto.class));
    }

    @Test
    @DisplayName("新增自建题：先同步建题，再写 is_plat=0 的索引")
    void addCustomQuestionWritesIndex() {
        groupQuestionService.addQuestion(CreateGroupQuestionDto.builder()
                .groupId(GROUP_ID)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .timeLimit(1000)
                .memoryLimit(256)
                .build());

        assertThat(jdbcTemplate.queryForObject(
                "select question_id from group_question where group_id = ?", Long.class, GROUP_ID))
                .isEqualTo(CUSTOM_QUESTION_ID);
        assertThat(jdbcTemplate.queryForObject(
                "select is_plat_from_question from group_question where group_id = ?", Integer.class, GROUP_ID))
                .isZero();
    }

    @Test
    @DisplayName("详情：跨组索引拒绝，本组索引正常返回")
    void getQuestionIsGroupScoped() {
        insertQuestion(OTHER_GROUP_ID, PLATFORM_QUESTION_ID, 1);
        long otherMappingId = mappingIdOf(OTHER_GROUP_ID, PLATFORM_QUESTION_ID);

        assertCode(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION,
                () -> groupQuestionService.getQuestion(otherMappingId, GROUP_ID));

        insertQuestion(GROUP_ID, PLATFORM_QUESTION_ID, 1);
        long mappingId = mappingIdOf(GROUP_ID, PLATFORM_QUESTION_ID);
        GroupQuestionVo vo = groupQuestionService.getQuestion(mappingId, GROUP_ID);
        assertThat(vo.groupQuestionId()).isEqualTo(mappingId);
        assertThat(vo.questionDto().getQuestionId()).isEqualTo(PLATFORM_QUESTION_ID);
    }

    @Test
    @DisplayName("修改：自建题使用索引里的 questionId 调下游，平台题拒绝")
    void updateCustomQuestionOnly() {
        insertQuestion(GROUP_ID, CUSTOM_QUESTION_ID, 0);
        long customMappingId = mappingIdOf(GROUP_ID, CUSTOM_QUESTION_ID);

        groupQuestionService.updateGroupQuestion(
                validUpdate().questionId(999L).title("改后标题").build(), customMappingId, GROUP_ID);

        verify(questionFeignClient).putQuestion(any(), org.mockito.ArgumentMatchers.argThat(
                (org.example.serviceapi.dto.question.UpdateGroupQuestionDto dto) ->
                        dto.questionId().equals(CUSTOM_QUESTION_ID) && "改后标题".equals(dto.title())));

        insertQuestion(GROUP_ID, PLATFORM_QUESTION_ID, 1);
        long platformMappingId = mappingIdOf(GROUP_ID, PLATFORM_QUESTION_ID);
        assertCode(QuizNestErrorCode.NOT_ALLOWED_UPDATE_PLATFORM_QUESTION,
                () -> groupQuestionService.updateGroupQuestion(
                        validUpdate().build(), platformMappingId, GROUP_ID));
    }

    @Test
    @DisplayName("删除：平台题只删索引，自建题先删下游再删索引")
    void deleteBranches() {
        insertQuestion(GROUP_ID, PLATFORM_QUESTION_ID, 1);
        long platformMappingId = mappingIdOf(GROUP_ID, PLATFORM_QUESTION_ID);
        groupQuestionService.deleteGroupQuestion(platformMappingId, GROUP_ID);
        verify(questionFeignClient, never()).deleteQuestion(any(), anyLong());

        insertQuestion(GROUP_ID, CUSTOM_QUESTION_ID, 0);
        long customMappingId = mappingIdOf(GROUP_ID, CUSTOM_QUESTION_ID);
        groupQuestionService.deleteGroupQuestion(customMappingId, GROUP_ID);
        verify(questionFeignClient).deleteQuestion(any(), eq(CUSTOM_QUESTION_ID));
        assertThat(rowCountOfGroup()).isZero();
    }

    @Test
    @DisplayName("压测：16 线程 × 50 次详情/修改混合，错误率 0、P95 < 500ms、索引不被破坏")
    void mixedReadWriteLoad() throws Exception {
        for (long questionId = 1000; questionId < 1020; questionId++) {
            insertQuestion(GROUP_ID, questionId, 0);
            when(questionFeignClient.getQuestionInfo(questionId))
                    .thenReturn(Result.success(QuestionDto.builder()
                            .questionId(questionId).status(3).createUserId(ROOT).build()));
        }
        List<Long> mappingIds = jdbcTemplate.queryForList(
                "select group_question_id from group_question where group_id = ? order by group_question_id",
                Long.class, GROUP_ID);

        // 预热，避免把 JIT/连接池冷启动算进 P95
        for (int i = 0; i < 50; i++) {
            groupQuestionService.getQuestion(mappingIds.get(i % mappingIds.size()), GROUP_ID);
        }

        int threads = 16;
        int iterations = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        long start = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(ROOT);
                    try {
                        for (int i = 0; i < iterations; i++) {
                            long mappingId = mappingIds.get(i % mappingIds.size());
                            long begin = System.nanoTime();
                            try {
                                if (i % 2 == 0) {
                                    groupQuestionService.getQuestion(mappingId, GROUP_ID);
                                } else {
                                    groupQuestionService.updateGroupQuestion(
                                            validUpdate().build(), mappingId, GROUP_ID);
                                }
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
        Collections.sort(sorted);
        long total = sorted.size();
        double qps = total / (elapsedNanos / 1_000_000_000.0);
        long p50 = sorted.get((int) (total * 0.50));
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) (total * 0.95)));
        long p99 = sorted.get(Math.min(sorted.size() - 1, (int) (total * 0.99)));
        System.out.printf("[LOAD-CRUD] requests=%d threads=%d qps=%.1f p50=%.2fms p95=%.2fms p99=%.2fms errors=%d%n",
                total, threads, qps, p50 / 1_000_000.0, p95 / 1_000_000.0, p99 / 1_000_000.0, errors.get());

        assertThat(errors.get()).as("压测错误数").isZero();
        assertThat(total).isEqualTo((long) threads * iterations);
        assertThat(p95 / 1_000_000.0).as("P95 延迟（毫秒）").isLessThan(500.0);
        assertThat(rowCountOfGroup()).isEqualTo(20L);
    }

    private void insertQuestion(long groupId, long questionId, int isPlatform) {
        jdbcTemplate.update("insert into group_question(question_id, create_user_id, group_id, is_plat_from_question) "
                + "values(?, ?, ?, ?)", questionId, ROOT, groupId, isPlatform);
    }

    private long mappingIdOf(long groupId, long questionId) {
        Long mappingId = jdbcTemplate.queryForObject(
                "select group_question_id from group_question where group_id = ? and question_id = ?",
                Long.class, groupId, questionId);
        return mappingId == null ? -1L : mappingId;
    }

    private long rowCountOfGroup() {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from group_question where group_id = ?", Long.class, GROUP_ID);
        return n == null ? 0 : n;
    }

    private static UpdateGroupQuestionDto.UpdateGroupQuestionDtoBuilder validUpdate() {
        return UpdateGroupQuestionDto.builder()
                .title("改后标题")
                .description("改后描述")
                .inputDesc("输入")
                .outputDesc("输出")
                .sampleInput("1 2")
                .sampleOutput("3")
                .timeLimit(2000)
                .memoryLimit(512);
    }

    private void assertCode(org.example.serviceapi.exception.ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}

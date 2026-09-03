package org.example.servicereview.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.ProgressTrackerDto;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.enums.ProgressTrackerStatus;
import org.example.servicereview.mapper.ProgressTrackerMapper;
import org.example.servicereview.vo.ProgressBatchCreateVo;
import org.example.servicereview.vo.ProgressBatchDeleteVo;
import org.example.servicereview.vo.ProgressTrackerBriefVo;
import org.example.servicereview.vo.WeeklyReportVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * ProgressTrackerService 的 agent 专用方法（批量创建/删除、active 列表、周报）单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressTrackerAgentOpsTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER = 8L;

    @Mock
    private ProgressTrackerMapper progressTrackerMapper;
    @Mock
    private QuestionFeignClient questionFeignClient;
    @InjectMocks
    private ProgressTrackerService progressTrackerService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        // 默认题目标题回填为空
        when(questionFeignClient.getFavoritesBrief(anyList()))
                .thenReturn(Result.success(List.<QuestionBriefDto>of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProgressTracker plan(long id, long questionId, int status, LocalDate beginTime, Long ownerId) {
        ProgressTracker p = new ProgressTracker();
        p.setProgressId(id);
        p.setUserId(ownerId);
        p.setQuestionId(questionId);
        p.setStatus(status);
        p.setBeginTime(beginTime);
        p.setNotesContent("备注" + id);
        p.setSummaryContent("反思" + id);
        return p;
    }

    private ProgressTrackerDto dto(long questionId, LocalDate beginTime) {
        ProgressTrackerDto d = new ProgressTrackerDto();
        d.setQuestionId(questionId);
        d.setBeginTime(beginTime);
        d.setNotesContent("题" + questionId + "计划");
        return d;
    }

    private QuestionBriefDto brief(long questionId, String title, int difficulty) {
        return QuestionBriefDto.builder().questionId(questionId).title(title).difficulty(difficulty).build();
    }

    // ---------- 批量创建 ----------

    @Test
    void batchCreate_success_counts() {
        when(progressTrackerMapper.insert(any(ProgressTracker.class))).thenReturn(1);
        ProgressBatchCreateVo vo = progressTrackerService.batchCreate(List.of(
                dto(1L, LocalDate.now()),          // 今天 → 进行中
                dto(2L, LocalDate.now().plusDays(1)) // 未来 → 未开始
        ));
        assertEquals(2, vo.getCreated());
        assertTrue(vo.getSkippedQuestionIds().isEmpty());
    }

    @Test
    void batchCreate_duplicate_skips() {
        when(progressTrackerMapper.insert(any(ProgressTracker.class)))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("dup"));
        ProgressBatchCreateVo vo = progressTrackerService.batchCreate(List.of(
                dto(1L, LocalDate.now().plusDays(1)),
                dto(2L, LocalDate.now().plusDays(1))
        ));
        assertEquals(1, vo.getCreated());
        assertEquals(List.of(2L), vo.getSkippedQuestionIds());
    }

    @Test
    void batchCreate_empty_or_overLimit_throws() {
        assertThrows(IllegalArgumentException.class, () -> progressTrackerService.batchCreate(List.of()));
        List<ProgressTrackerDto> tooMany = java.util.stream.IntStream.range(0, 201)
                .mapToObj(i -> dto((long) i, LocalDate.now().plusDays(i)))
                .toList();
        assertThrows(IllegalArgumentException.class, () -> progressTrackerService.batchCreate(tooMany));
    }

    @Test
    void batchCreate_pastDate_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.batchCreate(List.of(dto(1L, LocalDate.now().minusDays(1)))));
    }

    // ---------- 批量删除 ----------

    @Test
    void batchDelete_mixed_foreignAsNotFound() {
        when(progressTrackerMapper.selectById(1L)).thenReturn(plan(1L, 1L, 1, LocalDate.now(), USER_ID));
        when(progressTrackerMapper.selectById(2L)).thenReturn(plan(2L, 2L, 0, LocalDate.now().plusDays(1), OTHER_USER));
        when(progressTrackerMapper.selectById(3L)).thenReturn(null);

        ProgressBatchDeleteVo vo = progressTrackerService.batchDelete(List.of(1L, 2L, 3L, 2L));

        assertEquals(1, vo.getDeleted());
        assertEquals(List.of(2L, 3L), vo.getNotFoundIds());
    }

    @Test
    void batchDelete_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> progressTrackerService.batchDelete(List.of()));
    }

    // ---------- active 列表（瘦身 + 标题回填） ----------

    @Test
    void listActive_projectsAndEnrichesTitles() {
        ProgressTracker future = plan(1L, 11L, ProgressTrackerStatus.NOT_STARTED.getCode(), LocalDate.now().plusDays(2), USER_ID);
        ProgressTracker today = plan(2L, 22L, ProgressTrackerStatus.IN_PROGRESS.getCode(), LocalDate.now(), USER_ID);
        when(progressTrackerMapper.selectList(any())).thenReturn(List.of(today, future));
        when(questionFeignClient.getFavoritesBrief(anyList())).thenReturn(Result.success(List.of(
                brief(11L, "题11", 2), brief(22L, "题22", 1)
        )));

        List<ProgressTrackerBriefVo> vos = progressTrackerService.listActive();

        assertEquals(2, vos.size());
        assertEquals("题22", vos.get(0).getTitle());
        assertEquals(1, vos.get(0).getDifficulty());
        assertEquals("题11", vos.get(1).getTitle());
        // 短文本不被截断，原样返回（brief VO 无 submitIds 字段，天然瘦身）
        assertEquals("反思2", vos.get(0).getSummaryContent());
        assertEquals("备注2", vos.get(0).getNotesContent());
    }

    // ---------- 周报 ----------

    @Test
    void weeklyReport_aggregates() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        ProgressTracker done = plan(1L, 11L, ProgressTrackerStatus.COMPLETED.getCode(), monday, USER_ID);
        done.setSummaryContent("这道题理解了滑动窗口");
        ProgressTracker expired = plan(2L, 22L, ProgressTrackerStatus.EXPIRED.getCode(), monday.plusDays(1), USER_ID);
        when(progressTrackerMapper.selectList(any())).thenReturn(List.of(done, expired));
        when(questionFeignClient.getFavoritesBrief(anyList())).thenReturn(Result.success(List.of(brief(11L, "题11", 2))));

        WeeklyReportVo report = progressTrackerService.getWeeklyReport(today);

        assertEquals(monday, report.getStartDate());
        assertEquals(monday.plusDays(6), report.getEndDate());
        assertEquals(2, report.getTotalPlanned());
        assertEquals(1, report.getCompletedCount());
        assertEquals(1, report.getExpiredCount());
        assertEquals(7, report.getDaily().size());
        assertEquals(1, report.getCompletedItems().size());
        assertEquals("题11", report.getCompletedItems().get(0).getTitle());
        assertEquals("这道题理解了滑动窗口", report.getCompletedItems().get(0).getSummaryContent());
    }

    @Test
    void weeklyReport_emptyWeek_zeroFilled() {
        when(progressTrackerMapper.selectList(any())).thenReturn(List.of());
        WeeklyReportVo report = progressTrackerService.getWeeklyReport(LocalDate.now());
        assertEquals(0, report.getTotalPlanned());
        assertEquals(0, report.getCompletedCount());
        assertEquals(7, report.getDaily().size());
        assertTrue(report.getCompletedItems().isEmpty());
    }
}

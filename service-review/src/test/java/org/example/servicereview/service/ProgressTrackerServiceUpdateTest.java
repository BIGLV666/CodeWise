package org.example.servicereview.service;

import org.example.servicecommon.until.UserContext;
import org.example.servicereview.entry.ProgressTracker;
import org.example.servicereview.enums.ProgressTrackerStatus;
import org.example.servicereview.mapper.ProgressTrackerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * updateProgress 规则回归：备注/反思任何状态都可改；开始时间只在「未开始」或「已过期」时可改。
 * 重点锁定此前的缺陷——已完成计划被整体早抛，导致备注/总结也无法修改。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressTrackerServiceUpdateTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER = 8L;
    private static final Long PLAN_ID = 101L;

    @Mock
    private ProgressTrackerMapper progressTrackerMapper;

    @org.mockito.InjectMocks
    private ProgressTrackerService progressTrackerService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ProgressTracker plan(Integer status, LocalDate beginTime, Long ownerId) {
        ProgressTracker p = new ProgressTracker();
        p.setProgressId(PLAN_ID);
        p.setUserId(ownerId);
        p.setQuestionId(1L);
        p.setStatus(status);
        p.setBeginTime(beginTime);
        p.setNotesContent("旧备注");
        p.setSummaryContent("旧总结");
        return p;
    }

    @Test
    void completed_canUpdateNotesAndSummary() {
        ProgressTracker p = plan(ProgressTrackerStatus.COMPLETED.getCode(), LocalDate.now().minusDays(2), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        progressTrackerService.updateProgress(PLAN_ID, null, "新备注", "新总结");

        ArgumentCaptor<ProgressTracker> captor = ArgumentCaptor.forClass(ProgressTracker.class);
        verify(progressTrackerMapper).updateById(captor.capture());
        assertEquals("新备注", captor.getValue().getNotesContent());
        assertEquals("新总结", captor.getValue().getSummaryContent());
        // 开始时间未被改动，状态仍为已完成
        assertEquals(LocalDate.now().minusDays(2), captor.getValue().getBeginTime());
        assertEquals(ProgressTrackerStatus.COMPLETED.getCode(), captor.getValue().getStatus());
    }

    @Test
    void completed_cannotReschedule() {
        ProgressTracker p = plan(ProgressTrackerStatus.COMPLETED.getCode(), LocalDate.now().minusDays(2), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.updateProgress(PLAN_ID, LocalDate.now().plusDays(3), "备注", null));
        verify(progressTrackerMapper, never()).updateById(any(ProgressTracker.class));
    }

    @Test
    void inProgress_cannotReschedule() {
        ProgressTracker p = plan(ProgressTrackerStatus.IN_PROGRESS.getCode(), LocalDate.now(), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.updateProgress(PLAN_ID, LocalDate.now().plusDays(1), null, null));
    }

    @Test
    void expired_cannotReschedule() {
        ProgressTracker p = plan(ProgressTrackerStatus.EXPIRED.getCode(), LocalDate.now().minusDays(1), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.updateProgress(PLAN_ID, LocalDate.now().plusDays(2), null, null));
        verify(progressTrackerMapper, never()).updateById(any(ProgressTracker.class));
    }

    @Test
    void future_canReschedule() {
        ProgressTracker p = plan(ProgressTrackerStatus.NOT_STARTED.getCode(), LocalDate.now().plusDays(5), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        progressTrackerService.updateProgress(PLAN_ID, LocalDate.now().plusDays(10), null, null);

        verify(progressTrackerMapper).updateById(any(ProgressTracker.class));
    }

    @Test
    void rescheduleToPast_throws() {
        ProgressTracker p = plan(ProgressTrackerStatus.NOT_STARTED.getCode(), LocalDate.now().plusDays(1), USER_ID);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.updateProgress(PLAN_ID, LocalDate.now().minusDays(2), null, null));
        verify(progressTrackerMapper, never()).updateById(any(ProgressTracker.class));
    }

    @Test
    void foreignPlan_throws() {
        ProgressTracker p = plan(ProgressTrackerStatus.NOT_STARTED.getCode(), LocalDate.now().plusDays(1), OTHER_USER);
        when(progressTrackerMapper.selectById(PLAN_ID)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> progressTrackerService.updateProgress(PLAN_ID, null, "备注", null));
        verify(progressTrackerMapper, never()).updateById(any(ProgressTracker.class));
    }
}

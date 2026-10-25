package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import org.example.serviceapi.dto.CursorPageResult;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.servicequiznest.vo.MinimalQuestionVo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GroupQuestionService#cursorQuestion} 的游标分页、懒删除与降级行为。
 *
 * <p>核心不变量：</p>
 * <ol>
 *   <li>游标是索引主键，翻页不重不漏；末页 {@code nextCursor=null}；</li>
 *   <li>题目服务不存在的题目只删本地索引，删失败不影响列表；</li>
 *   <li>题目服务异常必须显式失败，不能把空结果当成「题目全被删」而清空题库。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionServiceCursorTest {

    private static final long GROUP_ID = 88L;
    private static final long USER_ID = 9L;

    @Mock
    private QuestionFeignClient questionFeignClient;

    @Mock
    private GroupMemberMapper groupMemberMapper;

    @Mock
    private GroupQuestionMapper groupQuestionMapper;

    @Mock
    private GroupQuestionRollbackPublisher rollbackPublisher;

    @Mock
    private GroupQuestionCleanupPublisher cleanupPublisher;

    private GroupQuestionService groupQuestionService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        groupQuestionService = new GroupQuestionService(
                questionFeignClient, groupMemberMapper, groupQuestionMapper,
                rollbackPublisher, cleanupPublisher, "test-token");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("groupId 为空/非正数：直接拒绝，不查成员也不查索引")
    void invalidGroupIdRejected() {
        assertCode(null, null, 20, QuizNestErrorCode.GROUP_ID_REQUIRED);
        assertCode(0L, null, 20, QuizNestErrorCode.INVALID_GROUP_ID);
        assertCode(-1L, null, 20, QuizNestErrorCode.INVALID_GROUP_ID);
        verifyNoInteractions(groupMemberMapper, groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("非小组成员：拒绝访问，不查题库")
    void nonMemberRejected() {
        when(groupMemberMapper.selectOne(any())).thenReturn(null);

        assertCode(GROUP_ID, null, 20, QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        verifyNoInteractions(groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("PENDING/REJECTED 占位记录不算成员，拒绝访问")
    void pendingOrRejectedMemberRejected() {
        GroupMember pending = new GroupMember();
        pending.setRole(GroupMemberRole.MEMBER);
        pending.setJoinStatus(GroupJoinStatus.PENDING);
        when(groupMemberMapper.selectOne(any())).thenReturn(pending);

        assertCode(GROUP_ID, null, 20, QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);

        GroupMember rejected = new GroupMember();
        rejected.setRole(GroupMemberRole.MEMBER);
        rejected.setJoinStatus(GroupJoinStatus.REJECTED);
        when(groupMemberMapper.selectOne(any())).thenReturn(rejected);

        assertCode(GROUP_ID, null, 20, QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        verifyNoInteractions(groupQuestionMapper, questionFeignClient);
    }

    @Test
    @DisplayName("空题库：返回空列表、nextCursor=null、hasNext=false，不调题目服务")
    void emptyGroupReturnsEmptyPage() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of());

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, null, 20);

        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getNextCursor()).isNull();
        assertThat(page.getHasNext()).isFalse();
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    @DisplayName("首页：多查一条判断 hasNext，游标取本页最后一条索引主键")
    void firstPageReturnsCursorOfLastConsumedRow() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(500L, 10L, 0), mapping(501L, 11L, 0), mapping(502L, 12L, 0)));
        when(questionFeignClient.getFavoritesBrief(List.of(10L, 11L)))
                .thenReturn(Result.success(List.of(brief(11L), brief(10L))));

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, null, 2);

        assertThat(page.getHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(501L);
        assertThat(page.getRecords()).extracting(MinimalQuestionVo::groupQuestionId)
                .containsExactly(500L, 501L);
        // 只批量查询本页题目，额外那一条不产生跨服务调用
        verify(questionFeignClient).getFavoritesBrief(List.of(10L, 11L));
        verify(groupQuestionMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("末页：hasNext=false 且 nextCursor=null")
    void lastPageHasNoNextCursor() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(mapping(510L, 20L, 1)));
        when(questionFeignClient.getFavoritesBrief(List.of(20L)))
                .thenReturn(Result.success(List.of(brief(20L))));

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, 509L, 20);

        assertThat(page.getHasNext()).isFalse();
        assertThat(page.getNextCursor()).isNull();
        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().getFirst().dto().getQuestionId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("游标只前进：本页尾部题目已失效时，nextCursor 仍取最后一条已消费索引")
    void cursorAdvancesEvenWhenTailRowsAreStale() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(600L, 30L, 0), mapping(601L, 31L, 0), mapping(602L, 32L, 0)));
        // 31 已被题目服务删除，本页只返回 30
        when(questionFeignClient.getFavoritesBrief(List.of(30L, 31L)))
                .thenReturn(Result.success(List.of(brief(30L))));

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, null, 2);

        assertThat(page.getHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(601L);
        assertThat(page.getRecords()).extracting(MinimalQuestionVo::groupQuestionId).containsExactly(600L);
        verify(groupQuestionMapper).deleteByIds(List.of(601L));
    }

    @Test
    @DisplayName("懒删除：题目服务缺失的索引被批量清理，其余记录按索引顺序返回")
    void staleMappingsAreDeletedLazily() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(700L, 40L, 0), mapping(701L, 41L, 1), mapping(702L, 42L, 0)));
        when(questionFeignClient.getFavoritesBrief(List.of(40L, 41L, 42L)))
                .thenReturn(Result.success(List.of(brief(42L), brief(40L))));

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, null, 20);

        assertThat(page.getHasNext()).isFalse();
        assertThat(page.getRecords()).extracting(MinimalQuestionVo::groupQuestionId)
                .containsExactly(700L, 702L);
        verify(groupQuestionMapper).deleteByIds(List.of(701L));
    }

    @Test
    @DisplayName("懒删除失败：只记日志，列表照常返回，不影响下一次重试")
    void lazyDeleteFailureDoesNotBreakQuery() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(800L, 50L, 0), mapping(801L, 51L, 0)));
        when(questionFeignClient.getFavoritesBrief(List.of(50L, 51L)))
                .thenReturn(Result.success(List.of(brief(50L))));
        doThrow(new RuntimeException("db down")).when(groupQuestionMapper).deleteByIds(List.of(801L));

        CursorPageResult<MinimalQuestionVo> page = groupQuestionService.cursorQuestion(GROUP_ID, null, 20);

        assertThat(page.getRecords()).extracting(MinimalQuestionVo::groupQuestionId).containsExactly(800L);
        verify(groupQuestionMapper).deleteByIds(List.of(801L));
    }

    @Test
    @DisplayName("题目服务返回 null / 非 200 / 抛异常：统一按下游不可用失败，且不懒删除")
    void questionServiceFailureIsExplicit() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(mapping(900L, 60L, 0)));
        when(questionFeignClient.getFavoritesBrief(List.of(60L)))
                .thenReturn(null)
                .thenReturn(Result.error("down", 500))
                .thenReturn(Result.success((List<QuestionBriefDto>) null))
                .thenThrow(new RuntimeException("timeout"));

        assertCode(GROUP_ID, null, 20, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCode(GROUP_ID, null, 20, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCode(GROUP_ID, null, 20, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        assertCode(GROUP_ID, null, 20, CommonErrorCode.DOWNSTREAM_UNAVAILABLE);

        verify(groupQuestionMapper, never()).deleteByIds(anyList());
    }

    @Test
    @DisplayName("pageSize：空/非正数取默认 20，超过上限截断到 50")
    void pageSizeIsNormalized() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of());

        groupQuestionService.cursorQuestion(GROUP_ID, null, null);
        groupQuestionService.cursorQuestion(GROUP_ID, null, 0);
        groupQuestionService.cursorQuestion(GROUP_ID, null, -5);
        groupQuestionService.cursorQuestion(GROUP_ID, null, 1000);

        ArgumentCaptor<Wrapper<GroupQuestion>> captor = wrapperCaptor();
        verify(groupQuestionMapper, times(4)).selectList(captor.capture());
        List<String> limitSql = captor.getAllValues().stream()
                .map(wrapper -> ((QueryWrapper<GroupQuestion>) wrapper).getSqlSegment())
                .toList();
        assertThat(limitSql).allSatisfy(sql -> assertThat(sql).contains("LIMIT"));
        assertThat(limitSql).extracting(sql -> sql.substring(sql.indexOf("LIMIT")))
                .containsExactly("LIMIT 21", "LIMIT 21", "LIMIT 21", "LIMIT 51");
    }

    @Test
    @DisplayName("lastId 为空/非正数视为首页，正数时追加主键大于条件")
    void lastIdBuildsCursorCondition() {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of());

        groupQuestionService.cursorQuestion(GROUP_ID, null, 10);
        groupQuestionService.cursorQuestion(GROUP_ID, 0L, 10);
        groupQuestionService.cursorQuestion(GROUP_ID, 123L, 10);

        ArgumentCaptor<Wrapper<GroupQuestion>> captor = wrapperCaptor();
        verify(groupQuestionMapper, times(3)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).doesNotContain("group_question_id >");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).doesNotContain("group_question_id >");
        assertThat(captor.getAllValues().get(2).getSqlSegment()).contains("group_question_id >");
    }

    @Test
    @DisplayName("并发读取：60 个请求互不串扰，各自返回自己的游标")
    void concurrentReadsAreIsolated() throws Exception {
        stubMember();
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(mapping(1000L, 70L, 0)));
        when(questionFeignClient.getFavoritesBrief(anyList()))
                .thenAnswer(invocation -> Result.success(List.of(brief(70L))));

        int total = 60;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<CursorPageResult<MinimalQuestionVo>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                long lastId = 100L + i;
                futures.add(pool.submit(() -> {
                    UserContext.setUserId(USER_ID);
                    try {
                        return groupQuestionService.cursorQuestion(GROUP_ID, lastId, 10);
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            for (Future<CursorPageResult<MinimalQuestionVo>> future : futures) {
                CursorPageResult<MinimalQuestionVo> page = future.get(10, TimeUnit.SECONDS);
                assertThat(page.getRecords()).hasSize(1);
                assertThat(page.getRecords().getFirst().groupQuestionId()).isEqualTo(1000L);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(groupQuestionMapper, times(total)).selectList(any());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<GroupQuestion>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    private void stubMember() {
        GroupMember member = new GroupMember();
        member.setRole(GroupMemberRole.MEMBER);
        member.setJoinStatus(GroupJoinStatus.APPROVED);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);
    }

    private void assertCode(Long groupId, Long lastId, Integer pageSize,
                            org.example.serviceapi.exception.ErrorCode expected) {
        assertThatThrownBy(() -> groupQuestionService.cursorQuestion(groupId, lastId, pageSize))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }

    private static GroupQuestion mapping(long mappingId, long questionId, int isPlatform) {
        return GroupQuestion.builder()
                .groupQuestionId(mappingId)
                .questionId(questionId)
                .groupId(GROUP_ID)
                .isPlatFromQuestion(isPlatform)
                .build();
    }

    private static QuestionBriefDto brief(long questionId) {
        return QuestionBriefDto.builder().questionId(questionId).title("题" + questionId).status(3).build();
    }
}

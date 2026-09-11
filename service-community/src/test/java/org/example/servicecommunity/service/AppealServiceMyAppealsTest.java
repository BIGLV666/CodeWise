package org.example.servicecommunity.service;

import org.example.servicecommunity.entry.Appeal;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.AppealMapper;
import org.example.servicecommunity.mapper.CommentMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.SolutionMapper;
import org.example.servicecommunity.vo.AppealVo;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 申诉用户侧（我的申诉）单元测试：历史列表按当前用户过滤与游标裁剪、
 * 详情的本人放行 / 越权拦截 / 不存在报错。
 */
class AppealServiceMyAppealsTest {

    private static final Long CURRENT_USER_ID = 42L;

    private AppealMapper appealMapper;
    private PostMapper postMapper;
    private SolutionMapper solutionMapper;
    private CommentMapper commentMapper;
    private AppealService appealService;

    @BeforeEach
    void setUp() {
        appealMapper = mock(AppealMapper.class);
        postMapper = mock(PostMapper.class);
        solutionMapper = mock(SolutionMapper.class);
        commentMapper = mock(CommentMapper.class);
        appealService = new AppealService();
        ReflectionTestUtils.setField(appealService, "appealMapper", appealMapper);
        ReflectionTestUtils.setField(appealService, "postMapper", postMapper);
        ReflectionTestUtils.setField(appealService, "solutionMapper", solutionMapper);
        ReflectionTestUtils.setField(appealService, "commentMapper", commentMapper);
        UserContext.setUserId(CURRENT_USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Appeal appeal(long appealId, Long userId) {
        Appeal appeal = new Appeal();
        appeal.setAppealId(appealId);
        appeal.setPostId(100L);
        appeal.setPostType(PostType.POST);
        appeal.setUserId(userId);
        appeal.setReason("误判，内容未违规");
        appeal.setTakeDownReason("疑似广告");
        appeal.setStatus(0);
        appeal.setCreateTime(LocalDateTime.of(2026, 9, 11, 10, 0));
        appeal.setUpdateTime(LocalDateTime.of(2026, 9, 11, 10, 0));
        return appeal;
    }

    private Post postWithTitle(String title) {
        Post post = new Post();
        post.setPostTitle(title);
        return post;
    }

    @Test
    void myAppealsTrimsOverflowAndCarriesCursor() {
        // 模拟一页请求 size=2，命中 3 条（多取 1 条用于探测 hasNext）
        when(appealMapper.selectList(any())).thenReturn(List.of(
                appeal(1L, CURRENT_USER_ID),
                appeal(2L, CURRENT_USER_ID),
                appeal(3L, CURRENT_USER_ID)));
        when(postMapper.selectById(100L)).thenReturn(postWithTitle("我的帖子"));

        var page = appealService.getMyAppeals(null, 2);

        assertEquals(2, page.getRecords().size());
        assertEquals(2L, page.getNextCursor());
        assertTrue(page.getHasNext());
        assertEquals(2, page.getTotal());
        // VO 映射：标题来自关联帖子；用户侧查询自己的申诉不回填申诉人信息
        AppealVo vo = page.getRecords().get(0);
        assertEquals("我的帖子", vo.getTitle());
        assertEquals(PostType.POST, vo.getPostType());
        assertNull(vo.getUser());
    }

    @Test
    void myAppealsExactPageHasNoNext() {
        when(appealMapper.selectList(any())).thenReturn(List.of(
                appeal(1L, CURRENT_USER_ID),
                appeal(2L, CURRENT_USER_ID)));

        var page = appealService.getMyAppeals(null, 2);

        assertEquals(2, page.getRecords().size());
        assertNull(page.getNextCursor());
        assertFalse(page.getHasNext());
    }

    @Test
    void myAppealDetailReturnsOwnedAppeal() {
        when(appealMapper.selectById(7L)).thenReturn(appeal(7L, CURRENT_USER_ID));
        when(postMapper.selectById(100L)).thenReturn(postWithTitle("被下架的帖子"));

        AppealVo vo = appealService.getMyAppealDetail(7L);

        assertEquals(7L, vo.getAppealId());
        assertEquals("误判，内容未违规", vo.getReason());
        assertEquals("被下架的帖子", vo.getTitle());
    }

    @Test
    void myAppealDetailRejectsForeignAppeal() {
        when(appealMapper.selectById(8L)).thenReturn(appeal(8L, 999L));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> appealService.getMyAppealDetail(8L));
        assertEquals("无权查看该申诉", e.getMessage());
    }

    @Test
    void myAppealDetailRejectsMissingAppeal() {
        when(appealMapper.selectById(9L)).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> appealService.getMyAppealDetail(9L));
        assertEquals("申诉不存在", e.getMessage());
    }
}

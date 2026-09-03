package org.example.servicecommunity.service;

import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.AgentCommentCreateDto;
import org.example.servicecommunity.Dto.AgentPostCreateDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.enums.PostStatus;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.AgentCreatedVo;
import org.example.servicecommunity.vo.AgentLikeResultVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.MyContentVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AgentCommunityService 单元测试：降序信息流切片、显式点赞幂等、
 * 发帖/评论重复 requestId 显式化、「我的内容」正文截断。
 */
class AgentCommunityServiceTest {

    private static final Long USER_ID = 7L;

    private PostMapper postMapper;
    private LikeRecordMapper likeRecordMapper;
    private PostService postService;
    private CommentService commentService;
    private LikeRecordService likeRecordService;
    private MyContentService myContentService;
    private AgentCommunityService agentCommunityService;

    @BeforeEach
    void setUp() {
        postMapper = mock(PostMapper.class);
        likeRecordMapper = mock(LikeRecordMapper.class);
        postService = mock(PostService.class);
        commentService = mock(CommentService.class);
        likeRecordService = mock(LikeRecordService.class);
        myContentService = mock(MyContentService.class);

        agentCommunityService = new AgentCommunityService();
        ReflectionTestUtils.setField(agentCommunityService, "postMapper", postMapper);
        ReflectionTestUtils.setField(agentCommunityService, "likeRecordMapper", likeRecordMapper);
        ReflectionTestUtils.setField(agentCommunityService, "postService", postService);
        ReflectionTestUtils.setField(agentCommunityService, "commentService", commentService);
        ReflectionTestUtils.setField(agentCommunityService, "likeRecordService", likeRecordService);
        ReflectionTestUtils.setField(agentCommunityService, "myContentService", myContentService);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Post post(long id) {
        return Post.builder()
                .postId(id)
                .postTitle("帖" + id)
                .postContent("正文" + id)
                .userId(USER_ID)
                .status(PostStatus.NORMAL)
                .build();
    }

    // ---------- 公开信息流（降序） ----------

    @Test
    void listPublicPosts_desc_slicesAndSetsCursor() {
        // DB 按降序返回 pageSize+1 条 → hasNext=true，截取前 pageSize 条
        when(postMapper.selectList(any())).thenReturn(List.of(post(30L), post(20L), post(10L)));

        CursorPageResult<HomePostVo> page = agentCommunityService.listPublicPosts(null, 2, true);

        assertTrue(page.getHasNext());
        assertEquals(2, page.getRecords().size());
        assertEquals(20L, page.getNextCursor());
        assertEquals(30L, page.getRecords().get(0).getPostId());
        // 标签/作者信息批量回填只针对本页记录
        verify(postService).fillPostDetails(anyList());
    }

    @Test
    void listPublicPosts_lastPage_noCursor() {
        when(postMapper.selectList(any())).thenReturn(List.of(post(5L)));

        CursorPageResult<HomePostVo> page = agentCommunityService.listPublicPosts(10L, 20, true);

        assertFalse(page.getHasNext());
        assertNull(page.getNextCursor());
        assertEquals(1, page.getRecords().size());
    }

    @Test
    void listPublicPosts_pageSizeInvalid_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> agentCommunityService.listPublicPosts(null, 101, true));
        assertThrows(IllegalArgumentException.class,
                () -> agentCommunityService.listPublicPosts(null, 0, true));
    }

    // ---------- 发帖（幂等显式化） ----------

    @Test
    void createPost_duplicate_returnsDuplicateFlag() {
        AgentPostCreateDto dto = new AgentPostCreateDto();
        dto.setPostTitle("标题");
        dto.setPostContent("正文");
        dto.setRequestId("uuid-1");
        when(postService.createPost(any(), org.mockito.ArgumentMatchers.eq("uuid-1"))).thenReturn(null);

        AgentCreatedVo vo = agentCommunityService.createPost(dto);

        assertTrue(vo.getDuplicate());
        assertNull(vo.getPostId());
    }

    @Test
    void createPost_success_returnsIdAndPendingStatus() {
        AgentPostCreateDto dto = new AgentPostCreateDto();
        dto.setPostTitle("标题");
        dto.setPostContent("正文");
        dto.setRequestId("uuid-1");
        Post created = Post.builder().postId(9L).status(PostStatus.PENDING).build();
        when(postService.createPost(any(), org.mockito.ArgumentMatchers.eq("uuid-1"))).thenReturn(created);

        AgentCreatedVo vo = agentCommunityService.createPost(dto);

        assertFalse(vo.getDuplicate());
        assertEquals(9L, vo.getPostId());
        assertEquals(PostStatus.PENDING, vo.getStatus());
    }

    @Test
    void createPost_missingRequestId_orOverLongContent_throws() {
        AgentPostCreateDto noRequestId = new AgentPostCreateDto();
        noRequestId.setPostTitle("标题");
        noRequestId.setPostContent("正文");
        assertThrows(IllegalArgumentException.class, () -> agentCommunityService.createPost(noRequestId));

        AgentPostCreateDto overLong = new AgentPostCreateDto();
        overLong.setPostTitle("标题");
        overLong.setPostContent("长".repeat(AgentCommunityService.MAX_POST_CONTENT + 1));
        overLong.setRequestId("uuid-1");
        assertThrows(IllegalArgumentException.class, () -> agentCommunityService.createPost(overLong));
    }

    // ---------- 评论（幂等显式化） ----------

    @Test
    void createComment_success_mapsFields() {
        AgentCommentCreateDto dto = new AgentCommentCreateDto();
        dto.setComment("评论");
        dto.setPostId(3L);
        dto.setRequestId("uuid-2");
        Comment created = Comment.builder().commentId(7L).postId(3L).status(PostStatus.NORMAL).build();
        when(commentService.createComment(any(), org.mockito.ArgumentMatchers.eq("uuid-2"))).thenReturn(created);

        AgentCreatedVo vo = agentCommunityService.createComment(dto);

        assertFalse(vo.getDuplicate());
        assertEquals(7L, vo.getCommentId());
        assertEquals(3L, vo.getPostId());
        assertEquals(PostStatus.NORMAL, vo.getStatus());
    }

    @Test
    void createComment_duplicate_returnsDuplicateFlag() {
        AgentCommentCreateDto dto = new AgentCommentCreateDto();
        dto.setComment("评论");
        dto.setPostId(3L);
        dto.setRequestId("uuid-2");
        when(commentService.createComment(any(), org.mockito.ArgumentMatchers.eq("uuid-2"))).thenReturn(null);

        AgentCreatedVo vo = agentCommunityService.createComment(dto);

        assertTrue(vo.getDuplicate());
        assertNull(vo.getCommentId());
    }

    // ---------- 显式点赞（幂等） ----------

    @Test
    void setLikeState_alreadyLiked_shortCircuits() {
        when(likeRecordMapper.selectCount(any())).thenReturn(1L);

        AgentLikeResultVo vo = agentCommunityService.setLikeState(PostType.POST, 11L, true);

        assertTrue(vo.getLiked());
        assertFalse(vo.getChanged());
        verifyNoInteractions(likeRecordService);
    }

    @Test
    void setLikeState_notLiked_likesViaToggle() {
        when(likeRecordMapper.selectCount(any())).thenReturn(0L);
        when(likeRecordService.PostLike(11L)).thenReturn(true);

        AgentLikeResultVo vo = agentCommunityService.setLikeState(PostType.POST, 11L, true);

        assertTrue(vo.getLiked());
        assertTrue(vo.getChanged());
        verify(likeRecordService).PostLike(11L);
    }

    @Test
    void setLikeState_unlikeWhenLiked() {
        when(likeRecordMapper.selectCount(any())).thenReturn(1L);
        when(likeRecordService.CommentLike(21L)).thenReturn(false);

        AgentLikeResultVo vo = agentCommunityService.setLikeState(PostType.COMMENT, 21L, false);

        assertFalse(vo.getLiked());
        assertTrue(vo.getChanged());
        verify(likeRecordService).CommentLike(21L);
    }

    @Test
    void setLikeState_unauthenticated_throws() {
        UserContext.clear();
        when(likeRecordMapper.selectCount(any())).thenReturn(0L);
        assertThrows(IllegalArgumentException.class,
                () -> agentCommunityService.setLikeState(PostType.POST, 11L, true));
        verify(likeRecordService, never()).PostLike(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setLikeState_solutionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> agentCommunityService.setLikeState(PostType.SOLUTION, 31L, true));
        verifyNoInteractions(likeRecordService);
    }

    @Test
    void parseTargetType_normalizesAndRejects() {
        assertEquals(PostType.POST, AgentCommunityService.parseTargetType("post"));
        assertEquals(PostType.COMMENT, AgentCommunityService.parseTargetType("COMMENT"));
        // SOLUTION 是合法枚举（解析层放行），点赞入口在 setLikeState 里拒绝
        assertEquals(PostType.SOLUTION, AgentCommunityService.parseTargetType("solution"));
        assertThrows(IllegalArgumentException.class, () -> AgentCommunityService.parseTargetType(null));
        assertThrows(IllegalArgumentException.class, () -> AgentCommunityService.parseTargetType("abc"));
    }

    // ---------- 我的内容（瘦身截断） ----------

    @Test
    void listMyContent_truncatesLongContentOnly() {
        MyContentVo longOne = MyContentVo.builder().targetId("1").type(PostType.POST)
                .content("字".repeat(300)).status(1).build();
        MyContentVo shortOne = MyContentVo.builder().targetId("2").type(PostType.POST)
                .content("短正文").status(0).build();
        when(myContentService.list(org.mockito.ArgumentMatchers.eq(PostType.POST),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(CursorPageResult.<MyContentVo>builder().records(new java.util.ArrayList<>(List.of(longOne, shortOne))).build());

        CursorPageResult<MyContentVo> page = agentCommunityService.listMyContent(PostType.POST, null, 20);

        String truncated = page.getRecords().get(0).getContent();
        assertTrue(truncated.startsWith("字".repeat(200)));
        assertTrue(truncated.endsWith("…[已截断，完整正文用详情接口查询]"));
        assertEquals("短正文", page.getRecords().get(1).getContent());
    }

    @Test
    void listMyContent_emptyRecords_ok() {
        when(myContentService.list(any(), any(), anyInt()))
                .thenReturn(CursorPageResult.<MyContentVo>builder().records(null).build());
        assertNull(agentCommunityService.listMyContent(PostType.COMMENT, null, 20).getRecords());
        verify(myContentService).list(any(), any(), anyInt());
    }
}

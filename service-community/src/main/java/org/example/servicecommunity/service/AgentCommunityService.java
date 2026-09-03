package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.AgentCommentCreateDto;
import org.example.servicecommunity.Dto.AgentPostCreateDto;
import org.example.servicecommunity.Dto.AgentPostUpdateDto;
import org.example.servicecommunity.Dto.CommentDto;
import org.example.servicecommunity.Dto.PostDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.enums.PostStatus;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.vo.AgentContentStatusVo;
import org.example.servicecommunity.vo.AgentCreatedVo;
import org.example.servicecommunity.vo.AgentLikeResultVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.MyContentVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * codewise-agent 专用社区服务：组合既有 PostService/CommentService/LikeRecordService/
 * MyContentService，补齐 agent 场景需要而网页端没有的能力。
 *
 * <p>与网页端的差异（安全语义不变，身份一律取自 UserContext）：</p>
 * <ul>
 *   <li>公开信息流支持降序（latest，从最新往回翻页），网页端只有升序；</li>
 *   <li>点赞从「切换」改为「显式终态」：先查当前状态，已处于期望态则幂等短路，
 *       避免网络重试导致赞状态震荡；</li>
 *   <li>发帖/发评论重复 requestId 显式返回 duplicate=true（网页端返回 data=null）；</li>
 *   <li>「我的内容」正文截断，控制 agent 上下文体积。</li>
 * </ul>
 */
@Service
public class AgentCommunityService {

    /** agent 批量列表单页上限（与网页端一致）。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 「我的内容」列表正文的截断长度；完整正文经详情接口获取。 */
    private static final int MY_CONTENT_TRUNCATE = 200;
    /** agent 侧正文/评论长度护栏（DB 为 LONGTEXT 无上限，防止模型超长输出）。 */
    public static final int MAX_POST_CONTENT = 20_000;
    public static final int MAX_COMMENT_LENGTH = 2_000;

    @Autowired
    private PostMapper postMapper;
    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private PostService postService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private LikeRecordService likeRecordService;
    @Autowired
    private MyContentService myContentService;

    /**
     * 公开帖子信息流（仅 status=1），支持升序/降序游标分页。
     *
     * @param lastId     游标：上一页返回的 nextCursor；首次不传
     * @param pageSize   页大小，1-100
     * @param descending true=latest（按 postId 降序，从最新往回），false=oldest（升序）
     */
    public CursorPageResult<HomePostVo> listPublicPosts(Long lastId, Integer pageSize, boolean descending) {
        int size = validatePageSize(pageSize);
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        if (lastId != null) {
            if (descending) {
                wrapper.lt(Post::getPostId, lastId);
            } else {
                wrapper.gt(Post::getPostId, lastId);
            }
        }
        // 信息流只返回审核通过的帖子，避免待审核/已下架内容外泄（与网页端一致）
        wrapper.eq(Post::getStatus, PostStatus.NORMAL);
        if (descending) {
            wrapper.orderByDesc(Post::getPostId);
        } else {
            wrapper.orderByAsc(Post::getPostId);
        }
        wrapper.last("LIMIT " + (size + 1));

        List<Post> queried = postMapper.selectList(wrapper);
        boolean hasNext = queried.size() > size;
        List<Post> records = hasNext ? queried.subList(0, size) : queried;

        List<HomePostVo> vos = new ArrayList<>();
        for (Post post : records) {
            vos.add(new HomePostVo(post));
        }
        // 标签/作者昵称/头像批量回填（复用网页端的批量装配，只 enrich 本页记录）
        postService.fillPostDetails(vos);

        return CursorPageResult.<HomePostVo>builder()
                .records(vos)
                .nextCursor(hasNext && !vos.isEmpty() ? vos.getLast().getPostId() : null)
                .hasNext(hasNext)
                .build();
    }

    /**
     * agent 发帖：requestId 必填做幂等；重复请求显式返回 duplicate=true。
     *
     * <p>新帖进入待审核（status=0），审核通过前仅作者本人与管理员可见。</p>
     */
    public AgentCreatedVo createPost(AgentPostCreateDto dto) {
        if (dto == null || dto.getRequestId() == null || dto.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (dto.getPostContent() != null && dto.getPostContent().length() > MAX_POST_CONTENT) {
            throw new IllegalArgumentException("帖子正文长度不能超过 " + MAX_POST_CONTENT + " 字符");
        }
        PostDto postDto = PostDto.builder()
                .postTitle(dto.getPostTitle())
                .postContent(dto.getPostContent())
                .tags(dto.getTags())
                .build();
        Post created = postService.createPost(postDto, dto.getRequestId());
        if (created == null) {
            return AgentCreatedVo.builder().duplicate(true).build();
        }
        return AgentCreatedVo.builder()
                .duplicate(false)
                .postId(created.getPostId())
                .status(created.getStatus())
                .build();
    }

    /**
     * agent 编辑自己的帖子：语义与网页端一致，编辑后重新进入待审核。
     */
    public AgentContentStatusVo updatePost(Long postId, AgentPostUpdateDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (dto.getPostContent() != null && dto.getPostContent().length() > MAX_POST_CONTENT) {
            throw new IllegalArgumentException("帖子正文长度不能超过 " + MAX_POST_CONTENT + " 字符");
        }
        org.example.servicecommunity.vo.PostVo postVo = new org.example.servicecommunity.vo.PostVo();
        postVo.setPostTitle(dto.getPostTitle());
        postVo.setPostContent(dto.getPostContent());
        postVo.setTags(dto.getTags());
        postService.updatePost(postId, postVo);
        return AgentContentStatusVo.builder()
                .id(postId)
                .status(PostStatus.PENDING)
                .note("已更新并重新进入待审核，审核通过前公开不可见")
                .build();
    }

    /**
     * agent 发表评论（仅社区帖子）：评论即时发布（status=1），重复 requestId 显式返回 duplicate。
     *
     * <p>回复某条评论时传 rootCommentId（可附 replyUserId/replyUserName）。</p>
     */
    public AgentCreatedVo createComment(AgentCommentCreateDto dto) {
        if (dto == null || dto.getRequestId() == null || dto.getRequestId().isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (dto.getComment() != null && dto.getComment().length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("评论长度不能超过 " + MAX_COMMENT_LENGTH + " 字符");
        }
        CommentDto commentDto = CommentDto.builder()
                .comment(dto.getComment())
                .postId(dto.getPostId())
                .rootCommentId(dto.getRootCommentId())
                .replyUserId(dto.getReplyUserId())
                .replyUserName(dto.getReplyUserName())
                .type(PostType.POST)
                .build();
        Comment created = commentService.createComment(commentDto, dto.getRequestId());
        if (created == null) {
            return AgentCreatedVo.builder().duplicate(true).build();
        }
        return AgentCreatedVo.builder()
                .duplicate(false)
                .commentId(created.getCommentId())
                .postId(created.getPostId())
                .status(created.getStatus())
                .build();
    }

    /**
     * 显式点赞/取消点赞（幂等）：先查 like_record 当前状态，已处于期望态直接短路返回。
     *
     * <p>说明：存在检查与切换动作之间有极小的并发窗口（同一用户双端同时操作），
     * 单 agent 重试场景不受影响；切换动作本身在 Redisson 锁内执行。</p>
     *
     * @param targetType 目标类型，仅支持 POST / COMMENT
     * @param targetId   目标 ID
     * @param wantLiked  期望终态：true=点赞，false=取消
     */
    public AgentLikeResultVo setLikeState(PostType targetType, Long targetId, boolean wantLiked) {
        if (targetType != PostType.POST && targetType != PostType.COMMENT) {
            throw new IllegalArgumentException("仅支持对帖子或评论点赞");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后操作");
        }
        String type = targetType.getType();
        boolean current = likeRecordMapper.selectCount(new QueryWrapper<LikeRecord>()
                .eq("post_id", targetId)
                .eq("user_id", userId)
                .eq("type", type)) > 0;
        if (current == wantLiked) {
            return AgentLikeResultVo.builder().liked(current).changed(false).build();
        }
        // 切换动作返回的是操作后的新状态
        boolean now = targetType == PostType.POST
                ? likeRecordService.PostLike(targetId)
                : likeRecordService.CommentLike(targetId);
        return AgentLikeResultVo.builder().liked(now).changed(true).build();
    }

    /**
     * 「我的内容」瘦身列表：复用网页端查询，正文截断到 {@link #MY_CONTENT_TRUNCATE} 字符
     * （帖子完整正文经详情接口获取），并附审核拒绝/下架原因。
     */
    public CursorPageResult<MyContentVo> listMyContent(PostType type, Long lastId, Integer pageSize) {
        CursorPageResult<MyContentVo> page = myContentService.list(type, lastId, pageSize);
        if (page.getRecords() != null) {
            for (MyContentVo record : page.getRecords()) {
                String content = record.getContent();
                if (content != null && content.length() > MY_CONTENT_TRUNCATE) {
                    record.setContent(content.substring(0, MY_CONTENT_TRUNCATE) + "…[已截断，完整正文用详情接口查询]");
                }
            }
        }
        return page;
    }

    /** 解析 agent 传入的目标类型字符串（大小写不敏感）。 */
    public static PostType parseTargetType(String targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        try {
            return PostType.valueOf(targetType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("targetType 仅支持 POST / COMMENT");
        }
    }

    private int validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        }
        return pageSize;
    }
}

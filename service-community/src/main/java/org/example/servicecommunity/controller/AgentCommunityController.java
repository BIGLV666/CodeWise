package org.example.servicecommunity.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.Dto.AgentCommentCreateDto;
import org.example.servicecommunity.Dto.AgentLikeRequestDto;
import org.example.servicecommunity.Dto.AgentPostCreateDto;
import org.example.servicecommunity.Dto.AgentPostUpdateDto;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.service.AgentCommunityService;
import org.example.servicecommunity.service.CommentService;
import org.example.servicecommunity.service.HostPostService;
import org.example.servicecommunity.service.MyContentService;
import org.example.servicecommunity.service.PostService;
import org.example.servicecommunity.vo.AgentContentStatusVo;
import org.example.servicecommunity.vo.AgentCreatedVo;
import org.example.servicecommunity.vo.AgentLikeResultVo;
import org.example.servicecommunity.vo.CommentVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.MyContentVo;
import org.example.servicecommunity.vo.PostVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * codewise-agent 专用社区接口（与网页端 {@link PostController} 等分离成类）。
 *
 * <p>差异点：公开信息流支持 latest 降序游标；点赞为显式终态（幂等）；
 * 发帖/发评论重复 requestId 显式返回 duplicate；「我的内容」正文截断。
 * 路径参数刻意改用 query 参数表达，保持与 agent 工具的静态路径白名单兼容。</p>
 *
 * <p>身份安全：与网页端一致，userId/userName 一律取自网关注入的 {@code UserContext}；
 * 删帖/删评论/编辑仅限作者本人，服务层校验。</p>
 */
@RestController
@RequestMapping("/api/community/agent")
public class AgentCommunityController {

    @Autowired
    private AgentCommunityService agentCommunityService;
    @Autowired
    private PostService postService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private HostPostService hostPostService;
    @Autowired
    private MyContentService myContentService;

    /**
     * 公开帖子信息流（仅审核通过的帖子）：order=latest 按最新往回翻（降序），oldest 为升序。
     */
    @GetMapping("/posts")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<HomePostVo>> listPosts(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "latest") String order) {
        boolean descending = switch (order == null ? "" : order.toLowerCase()) {
            case "latest" -> true;
            case "oldest" -> false;
            default -> throw new IllegalArgumentException("order 仅支持 latest / oldest");
        };
        return Result.success(agentCommunityService.listPublicPosts(lastId, pageSize, descending));
    }

    /**
     * 当前热度最高的帖子，最多 10 条。
     */
    @GetMapping("/posts/hot")
    @RateLimit(limit = 100, window = 60)
    public Result<List<HomePostVo>> hotPosts() {
        return Result.success(hostPostService.getHostHomePost());
    }

    /**
     * 按标题关键词或完整标签搜索帖子（二者必须且只能提供一个），按发布时间倒序。
     */
    @GetMapping("/posts/search")
    @RateLimit(limit = 100, window = 60)
    public Result<List<HomePostVo>> searchPosts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "20") Integer limit) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasTag = tag != null && !tag.isBlank();
        if (hasKeyword == hasTag) {
            throw new IllegalArgumentException("keyword 与 tag 必须且只能提供一个");
        }
        List<HomePostVo> posts = hasKeyword
                ? postService.searchPostsByTitle(keyword, limit)
                : postService.searchPostsByTag(tag, limit);
        return Result.success(posts);
    }

    /**
     * 帖子详情：正文、标签、点赞状态、相关帖子推荐；待审核/已下架内容仅作者本人可见。
     */
    @GetMapping("/post")
    @RateLimit(limit = 100, window = 60)
    public Result<PostVo> getPost(@RequestParam Long postId) {
        return Result.success(postService.getPostById(postId));
    }

    /**
     * 发帖：requestId 由 agent 客户端生成做幂等；新帖进入待审核（status=0）。
     */
    @PostMapping("/post")
    @RateLimit(limit = 20, window = 60)
    public Result<AgentCreatedVo> createPost(@RequestBody AgentPostCreateDto dto) {
        return Result.success(agentCommunityService.createPost(dto));
    }

    /**
     * 编辑自己的帖子：编辑后重新进入待审核，审核通过前公开不可见。
     */
    @PutMapping("/post")
    @RateLimit(limit = 20, window = 60)
    public Result<AgentContentStatusVo> updatePost(@RequestParam Long postId,
                                                   @RequestBody AgentPostUpdateDto dto) {
        return Result.success(agentCommunityService.updatePost(postId, dto));
    }

    /**
     * 删除自己的帖子（硬删除，不可恢复；评论与点赞异步级联清理）。
     */
    @DeleteMapping("/post")
    @RateLimit(limit = 20, window = 60)
    public Result<String> deletePost(@RequestParam Long postId) {
        postService.deletePostById(postId);
        return Result.success("success");
    }

    /**
     * 帖子评论列表（仅 POST 类型，公开可见的评论），游标分页，含当前用户点赞状态。
     */
    @GetMapping("/comments")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<CommentVo>> listComments(
            @RequestParam Long postId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Long rootCommentId) {
        return Result.success(commentService.cursorQuestions(lastId, pageSize, postId, rootCommentId, PostType.POST));
    }

    /**
     * 发表评论（即时可见）：回复某条评论时传 rootCommentId。
     */
    @PostMapping("/comments")
    @RateLimit(limit = 60, window = 60)
    public Result<AgentCreatedVo> createComment(@RequestBody AgentCommentCreateDto dto) {
        return Result.success(agentCommunityService.createComment(dto));
    }

    /**
     * 删除自己的评论（根评论会级联删除其下回复，异步清理）。
     */
    @DeleteMapping("/comments")
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteComment(@RequestParam Long commentId) {
        commentService.deleteComment(commentId);
        return Result.success("success");
    }

    /**
     * 显式点赞/取消点赞（幂等）：targetType=POST/COMMENT，action=like/unlike。
     */
    @PostMapping("/likes")
    @RateLimit(limit = 60, window = 60)
    public Result<AgentLikeResultVo> setLikeState(@RequestBody AgentLikeRequestDto dto) {
        if (dto == null || dto.getAction() == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        boolean wantLiked = switch (dto.getAction().toLowerCase()) {
            case "like" -> true;
            case "unlike" -> false;
            default -> throw new IllegalArgumentException("action 仅支持 like / unlike");
        };
        PostType targetType = AgentCommunityService.parseTargetType(dto.getTargetType());
        return Result.success(agentCommunityService.setLikeState(targetType, dto.getTargetId(), wantLiked));
    }

    /**
     * 我发布的内容（正文截断，附审核状态与拒绝原因）；type=POST/COMMENT/SOLUTION。
     */
    @GetMapping("/mine")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<MyContentVo>> listMyContent(
            @RequestParam PostType type,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.success(agentCommunityService.listMyContent(type, lastId, pageSize));
    }
}

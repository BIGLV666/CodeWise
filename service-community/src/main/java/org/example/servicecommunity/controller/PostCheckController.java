package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommon.aop.RequireAdmin;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.service.PostCheckService;
import org.example.servicecommunity.vo.CheckDetailVo;
import org.example.servicecommunity.vo.CommentVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.HomeSolutionVo;
import org.example.servicecommunity.vo.TakeDownPostVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容审核台接口，整个类都要求管理员身份。
 * 待审核列表按 ID 游标分页；审核接口传入目标状态：1-通过/上架，2-拒绝/下架。
 */
@RequireAdmin
@RateLimit(limit = 120, window = 60)
@RestController
@RequestMapping("/api/community/check")
public class PostCheckController {

    @Autowired
    private PostCheckService postCheckService;

    // ============ 帖子 ============

    @GetMapping("/post/list")
    public Result<CursorPageResult<HomePostVo>> listPosts(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(postCheckService.cursorPost(lastId, pageSize));
    }

    @GetMapping("/post/detail")
    public Result<CheckDetailVo> postDetail(@RequestParam Long postId) {
        return Result.success(postCheckService.getPostDetail(postId));
    }

    /** 审核待审核帖子：status 1-通过 2-拒绝。 */
    @PostMapping("/post")
    public Result<Void> checkPost(@RequestParam Long postId, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        postCheckService.checkPost(postId, status, reason);
        return Result.success(null);
    }

    /** 对已发布帖子下架或恢复：status 2-下架 1-恢复。 */
    @PostMapping("/post/status")
    public Result<Void> updatePostStatus(@RequestParam Long postId, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        postCheckService.TackDownPost(postId, status, reason);
        return Result.success(null);
    }

    // ============ 评论 ============

    @GetMapping("/comment/list")
    public Result<CursorPageResult<CommentVo>> listComments(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long rootCommentId,
            @RequestParam(required = false) PostType type) {
        return Result.success(postCheckService.cursorQuestions(lastId, pageSize, rootCommentId, type));
    }

    @GetMapping("/comment/detail")
    public Result<CheckDetailVo> commentDetail(@RequestParam Long commentId) {
        return Result.success(postCheckService.getCommentDetail(commentId));
    }

    /**
     * 对评论下架或恢复：status 2-下架 1-恢复。
     *
     * <p>评论采用即时发布，没有待审核态，因此不提供「审核通过 / 拒绝」入口。</p>
     */
    @PostMapping("/comment/status")
    public Result<Void> updateCommentStatus(@RequestParam Long commentId, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        postCheckService.UpdateComment(commentId, status, reason);
        return Result.success(null);
    }

    // ============ 题解 ============

    @GetMapping("/solution/list")
    public Result<CursorPageResult<HomeSolutionVo>> listSolutions(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(postCheckService.listSolutions(lastId, pageSize));
    }

    @GetMapping("/solution/detail")
    public Result<CheckDetailVo> solutionDetail(@RequestParam Long solutionId) {
        return Result.success(postCheckService.getSolutionDetail(solutionId));
    }

    /** 审核待审核题解：status 1-通过 2-拒绝。 */
    @PostMapping("/solution")
    public Result<Void> checkSolution(@RequestParam Long solutionId, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        postCheckService.checkSolution(solutionId, status, reason);
        return Result.success(null);
    }

    /** 对已发布题解下架或恢复：status 2-下架 1-恢复。 */
    @PostMapping("/solution/status")
    public Result<Void> updateSolutionStatus(@RequestParam Long solutionId, @RequestParam Integer status, @RequestParam(required = false) String reason) {
        postCheckService.updateSolution(solutionId, status, reason);
        return Result.success(null);
    }

    // ============ 通用详情 ============

    /** 按类型统一查询审核详情，type 取 POST/COMMENT/SOLUTION。 */
    @GetMapping("/detail")
    public Result<CheckDetailVo> detail(@RequestParam PostType type, @RequestParam Long targetId) {
        return Result.success(postCheckService.getDetail(type, targetId));
    }

    // ============ 下架记录 ============

    /** 获取下架记录列表。 */
    @GetMapping("/takedown/list")
    public Result<CursorPageResult<TakeDownPostVo>> listTakeDowns(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(postCheckService.getTakeDownList(lastId, pageSize));
    }
}

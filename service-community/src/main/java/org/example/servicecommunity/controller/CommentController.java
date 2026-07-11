package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.Dto.CommentDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.service.CommentService;
import org.example.servicecommunity.vo.CommentVo;
import org.example.servicecommunity.vo.CursorPageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/comments")
public class CommentController {

    @Autowired
    private CommentService commentService;

    /**
     * 发布评论或回复。requestId 通过 /api/community/request-id 获取。
     */
    @PostMapping
    public Result<Comment> createComment(@RequestBody CommentDto commentDto,
                                         @RequestParam String requestId) {
        return Result.success(commentService.createComment(commentDto, requestId));
    }

    /**
     * 查询帖子评论。rootCommentId=-1 不按根评论筛选，否则查询指定根评论的回复。
     */
    @GetMapping
    public Result<CursorPageResult<CommentVo>> listComments(
            @RequestParam Long postId,
            @RequestParam(defaultValue = "-1") Long rootCommentId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        validatePageSize(pageSize);
        return Result.success(commentService.cursorQuestions(lastId, pageSize, postId, rootCommentId));
    }

    @DeleteMapping("/{commentId}")
    public Result<String> deleteComment(@PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return Result.success("success");
    }

    private void validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
    }
}

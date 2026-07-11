package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.service.LikeRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/likes")
public class LikeController {

    @Autowired
    private LikeRecordService likeRecordService;

    /**
     * 切换当前用户对帖子的点赞状态，true 表示已点赞，false 表示已取消。
     */
    @PutMapping("/posts/{postId}")
    public Result<Boolean> togglePostLike(@PathVariable Long postId) {
        return Result.success(likeRecordService.PostLike(postId));
    }

    /**
     * 切换当前用户对评论的点赞状态，true 表示已点赞，false 表示已取消。
     */
    @PutMapping("/comments/{commentId}")
    public Result<Boolean> toggleCommentLike(@PathVariable Long commentId) {
        return Result.success(likeRecordService.CommentLike(commentId));
    }
}

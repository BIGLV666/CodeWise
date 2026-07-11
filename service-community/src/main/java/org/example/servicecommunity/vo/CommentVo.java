package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicecommunity.entry.Comment;

import java.time.LocalDateTime;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentVo {
    private Long commentId;
    private String comment;
    private String userId;
    private String userName;
    private Long postId;
    private Long rootCommentId;
    private Long replyUserId;
    private String replyUserName;
    private Long likeCount;
    private Boolean isLike;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public  CommentVo(Comment comment) {
        this.commentId = comment.getCommentId();
        this.comment = comment.getComment();
        this.userId = comment.getUserId().toString();
        this.userName = comment.getUserName();
        this.postId = comment.getPostId();
        this.rootCommentId = comment.getRootCommentId();
        this.replyUserId = comment.getReplyUserId();
        this.replyUserName = comment.getReplyUserName();
        this.likeCount = comment.getLikeCount();
        this.createTime = comment.getCreateTime();
        this.updateTime = comment.getUpdateTime();
    }
}

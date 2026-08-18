package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;
import java.util.List;

/** 当前用户查看自己发布内容时使用的统一结构。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyContentVo {
    private String targetId;
    private PostType type;
    private String title;
    private String content;
    /** 0-待审核，1-正常，2-审核拒绝或已下架。 */
    private Integer status;
    /** 审核拒绝或下架原因。 */
    private String rejectReason;
    private List<String> tags;
    /** 评论所属帖子或题解 ID。 */
    private String rootId;
    /** 评论所属内容类型；帖子和题解自身为 null。 */
    private PostType rootType;
    private String questionId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static MyContentVo of(Post post) {
        return MyContentVo.builder()
                .targetId(post.getPostId().toString())
                .type(PostType.POST)
                .title(post.getPostTitle())
                .content(post.getPostContent())
                .status(post.getStatus())
                .createTime(post.getCreateTime())
                .updateTime(post.getUpdateTime())
                .build();
    }

    public static MyContentVo of(Solution solution) {
        return MyContentVo.builder()
                .targetId(solution.getSolutionId().toString())
                .type(PostType.SOLUTION)
                .title(solution.getSolutionTitle())
                .content(solution.getSolutionContent())
                .status(solution.getStatus())
                .questionId(solution.getQuestionId() == null ? null : solution.getQuestionId().toString())
                .createTime(solution.getCreateTime())
                .updateTime(solution.getUpdateTime())
                .build();
    }

    public static MyContentVo of(Comment comment) {
        return MyContentVo.builder()
                .targetId(comment.getCommentId().toString())
                .type(PostType.COMMENT)
                .content(comment.getComment())
                .status(comment.getStatus())
                .rootId(comment.getPostId() == null ? null : comment.getPostId().toString())
                .rootType(comment.getType())
                .createTime(comment.getCreateTime())
                .updateTime(comment.getUpdateTime())
                .build();
    }
}

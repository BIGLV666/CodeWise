package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.dto.user.UserDto;
import org.example.servicecommunity.entry.Comment;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Solution;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核台使用的内容详情，帖子、评论、题解共用一个结构，便于前端统一渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckDetailVo {
    /** 被审核内容自身的 ID：帖子 ID、评论 ID 或题解 ID。 */
    private Long targetId;
    /** 内容类型：POST、COMMENT、SOLUTION。 */
    private PostType type;
    /** 帖子/题解标题；评论没有标题时为 null。 */
    private String title;
    /** 正文内容。 */
    private String content;
    /** 作者 ID，字符串形式避免前端精度丢失。 */
    private String userId;
    /** 作者信息，跨库通过 Feign 获取。 */
    private UserDto userDto;
    /** 0-待审核 1-正常 2-下架。 */
    private Integer status;
    private List<String> tags;
    /** 评论所属的帖子或题解 ID；帖子和题解详情为 null。 */
    private Long postId;
    /** 评论所在根评论 ID；非评论或一级评论为 null。 */
    private Long rootCommentId;
    /** 题解所属题目 ID；非题解为 null。 */
    private Long questionId;
    private Long likeCount;
    private Long commentCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static CheckDetailVo of(Post post) {
        return CheckDetailVo.builder()
                .targetId(post.getPostId())
                .type(PostType.POST)
                .title(post.getPostTitle())
                .content(post.getPostContent())
                .userId(post.getUserId() == null ? null : post.getUserId().toString())
                .status(post.getStatus())
                .likeCount(post.getLikeCount())
                .commentCount(post.getCommentCount())
                .createTime(post.getCreateTime())
                .updateTime(post.getUpdateTime())
                .build();
    }

    public static CheckDetailVo of(Comment comment) {
        return CheckDetailVo.builder()
                .targetId(comment.getCommentId())
                .type(PostType.COMMENT)
                .content(comment.getComment())
                .userId(comment.getUserId() == null ? null : comment.getUserId().toString())
                .status(comment.getStatus())
                .postId(comment.getPostId())
                .rootCommentId(comment.getRootCommentId())
                .likeCount(comment.getLikeCount())
                .createTime(comment.getCreateTime())
                .updateTime(comment.getUpdateTime())
                .build();
    }

    public static CheckDetailVo of(Solution solution) {
        return CheckDetailVo.builder()
                .targetId(solution.getSolutionId())
                .type(PostType.SOLUTION)
                .title(solution.getSolutionTitle())
                .content(solution.getSolutionContent())
                .userId(solution.getSolutionUserId() == null ? null : solution.getSolutionUserId().toString())
                .status(solution.getStatus())
                .questionId(solution.getQuestionId())
                .likeCount(solution.getLikeCount())
                .commentCount(solution.getCommentCount())
                .createTime(solution.getCreateTime())
                .updateTime(solution.getUpdateTime())
                .build();
    }
}

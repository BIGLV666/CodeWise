package org.example.servicecommunity.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicecommunity.enums.PostType;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentDto {

    private String comment;
    private Long postId;
    private Long rootCommentId;
    private Long replyUserId;
    private String replyUserName;
    /** 评论目标类型，不传时默认评论社区帖子。 */
    private PostType type;
}

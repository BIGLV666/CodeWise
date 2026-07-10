package org.example.servicecommunity.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}

package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Comment {
    @TableId(type = IdType.AUTO)
    private Long commentId;
    private String comment;
    private Long userId;
    private String userName;
    private Long postId;
    private Long rootCommentId;
    private Long replyUserId;
    private String replyUserName;
    private Long likeCount;
    private Integer status;//0-审核1-删除2-下架
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

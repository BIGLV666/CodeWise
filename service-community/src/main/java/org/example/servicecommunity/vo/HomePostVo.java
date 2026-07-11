package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.dto.UserDto;
import org.example.servicecommunity.entry.Post;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HomePostVo {
    private Long postId;
    private String postTitle;
    private List<String> tags;
    private String userId;
    private String userName;
    private Long likeCount;
    private String avatar;
    private Long commentCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public  HomePostVo(Post post){
        this.postId=post.getPostId();
        this.postTitle=post.getPostTitle();
        this.userId=post.getUserId().toString();
        this.userName=post.getUserName();
        this.likeCount=post.getLikeCount();
        this.commentCount=post.getCommentCount();
        this.createTime=post.getCreateTime();
        this.updateTime=post.getUpdateTime();
    }
}

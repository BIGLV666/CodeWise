package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.dto.user.UserDto;
import org.example.servicecommunity.entry.Post;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostVo {
    private Long postId;
    private String postTitle;
    private String postContent;
    private String userId;
    private String userName;
    private Long likeCount;
    private Long commentCount;
    private Boolean isLike;
    private List<String> tags;
    private Map<String,Map<Long,String>>relatedPost;
    private UserDto userDto;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public  PostVo(Post post){
        this.postId=post.getPostId();
        this.postTitle=post.getPostTitle();
        this.userId=post.getUserId().toString();
        this.userName=post.getUserName();
        this.likeCount=post.getLikeCount();
        this.commentCount=post.getCommentCount();
        this.postContent=post.getPostContent();
        this.createTime=post.getCreateTime();
        this.updateTime=post.getUpdateTime();
    }
}

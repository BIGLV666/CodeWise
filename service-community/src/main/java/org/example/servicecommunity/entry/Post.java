package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Post {
    @TableId(type = IdType.AUTO)
    private Long postId;
    private String postTitle;
    private String postContent;
    private Long userId;
    private String userName;
    @Builder.Default
    private Long likeCount=0L;
    private Long commentCount;
    private Integer status;//0-审核1-删除2-下架
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}

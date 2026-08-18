package org.example.servicecommunity.vo;

import lombok.Data;
import org.example.serviceapi.dto.user.UserDto;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;

@Data
public class AppealVo {
    private Long appealId;
    private Long postId;
    private PostType postType;
    private String title;
    private String reason;
    private String takeDownReason;
    /**
     * 0-待审核 1-拒绝 2-恢复
     */
    private Integer status;
    private UserDto user;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

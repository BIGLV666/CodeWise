package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import org.example.servicecommunity.Dto.AppealDto;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;

@Data
public class Appeal {
    @TableId(type = IdType.AUTO)
    private Long appealId;
    private Long postId;
    private PostType postType;
    private Long userId;
    /**
     * 申诉理由
     */
    private String reason;
    /**
     * 下架原因
     */
    private String takeDownReason;
    /**
     * 0-待审核 1-拒绝 2-恢复
     */
    private Integer status;

    /**
     * 操作人id
     */
    private Long adminUserId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Appeal() {
    }

    public Appeal(AppealDto dto){
        this.appealId = dto.getAppealId();
        this.postId = dto.getPostId();

        // 简化枚举转换：AppealDto.postType 是 String 类型
        String postTypeStr = dto.getPostType();
        if (postTypeStr == null || postTypeStr.trim().isEmpty()) {
            this.postType = null;
        } else {
            try {
                this.postType = PostType.valueOf(postTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 如果字符串不匹配任何枚举值，设为 null
                this.postType = null;
            }
        }

        this.reason = dto.getReason();
    }
}

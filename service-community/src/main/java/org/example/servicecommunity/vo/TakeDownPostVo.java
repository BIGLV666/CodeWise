package org.example.servicecommunity.vo;

import lombok.Data;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;

/**
 * 下架记录 VO
 */
@Data
public class TakeDownPostVo {
    private Long takeDownId;
    private PostType rootType;
    private Long rootId;
    private Long rootCommentId;
    private Long questionId;
    private String reason;
    private Long adminId;
    private String adminName;
    private LocalDateTime createTime;
    
    // 关联内容信息
    private String title;
    private String content;
}

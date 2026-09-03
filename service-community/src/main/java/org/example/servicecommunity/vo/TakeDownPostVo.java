package org.example.servicecommunity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;

/**
 * 下架记录 VO
 */
@Data
public class TakeDownPostVo {
    /** 下架记录 ID（take_down_post.id 为雪花 ID，超过 JS Number 2^53 精度，序列化为字符串）。 */
    @JsonSerialize(using = ToStringSerializer.class)
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

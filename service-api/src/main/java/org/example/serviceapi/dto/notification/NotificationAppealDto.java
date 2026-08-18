package org.example.serviceapi.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 申诉通知扩展数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationAppealDto {
    /** 申诉 ID */
    private Long appealId;
    
    /** 内容 ID */
    private Long postId;
    
    /** 内容类型（POST/COMMENT/SOLUTION） */
    private String postType;
    
    /** 内容标题 */
    private String title;
    
    /** 用户申诉理由 */
    private String reason;
    
    /** 管理员回复 */
    private String adminReason;
    
    /** 申诉是否通过（用户收到的处理结果通知） */
    private Boolean passed;
    
    /** 申诉用户 ID（管理员收到的提交通知） */
    private Long userId;
}

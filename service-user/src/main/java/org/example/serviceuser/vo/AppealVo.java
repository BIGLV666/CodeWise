package org.example.serviceuser.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppealVo {
    private Long userAppealId;
    /**
     * 申诉人id
     */
    private Long userId;
    /**
     * 申诉原因
     */
    private String appealReason;
    /**
     * 附件发送的邮箱
     */
    private String reasonEmail;
    /**
     * 冻结至
     */
    private LocalDateTime banTime;

    private String banReason;//冻结原因


}

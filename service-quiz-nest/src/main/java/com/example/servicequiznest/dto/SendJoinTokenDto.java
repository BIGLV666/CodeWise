package com.example.servicequiznest.dto;

import lombok.Data;

/**
 * 发送小组邀请码请求体。
 */
@Data
public class SendJoinTokenDto {

    /** 被邀请人 ID。 */
    private Long toUserId;

    /** 邀请人留言，可空，最多 200 字。 */
    private String content;
}

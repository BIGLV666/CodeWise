package com.example.servicequiznest.dto;

import lombok.Data;

/**
 * 发送小组广播请求体。
 */
@Data
public class SendGroupBroadcastDto {

    /** 广播正文，不能为空。 */
    private String context;
}

package org.example.servicecommon.dto;

import lombok.Data;

@Data
public class WebsocketSendDto {
    private Long userId;
    private String queueName;
    private Object result;

}

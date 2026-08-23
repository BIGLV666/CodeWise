package org.example.servicecommon.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * 邮件发送消息体。
 *
 * <p>{@code eventId} 为全局唯一事件 ID，消费端以此做数据库幂等与失败留存；
 * 旧格式消息（无该字段）由消费端生成一次性 ID 兜底。</p>
 */
@Data
@NoArgsConstructor
public class EmailMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 全局唯一事件 ID，消费端幂等键 */
    private String eventId;
    private String to;
    private String subject;
    private String content;

    public EmailMessage(String to, String subject, String content) {
        this.eventId = UUID.randomUUID().toString();
        this.to = to;
        this.subject = subject;
        this.content = content;
    }
}

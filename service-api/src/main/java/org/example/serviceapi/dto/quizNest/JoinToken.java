package org.example.serviceapi.dto.quizNest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 小组邀请码事件（quiz-nest -> message，经 Outbox 信封投递）。
 *
 * <p>生产端在发邀请的事务内发布，消费端写入 {@code notification_center} 站内信。
 * 时间字段统一用字符串（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code LocalDateTime}：
 * 消费端 {@code EnvelopeCodec} 使用未注册 JavaTimeModule 的 ObjectMapper，
 * 传 {@code LocalDateTime} 会在反序列化阶段失败并被判为毒消息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 幂等键，格式 {@code quiz:join-token:{groupId}:{toUserId}:{token}}。
     * 每次发送都会生成新邀请码，因此同一次邀请的重投可幂等，重新发送则是一条新站内信。
     */
    private String messageId;

    /** 邀请码（6 位数字，有效期见 RedisContext.JOIN_GROUP_TOKEN_TTL_SECONDS）。 */
    private String token;

    /** 小组 ID。 */
    private Long groupId;

    /** 被邀请人 ID（站内信接收人）。 */
    private Long toUserId;

    /** 邀请人留言，可为空。 */
    private String context;

    /** 小组名。 */
    private String groupName;

    /** 邀请人展示名（昵称优先，缺失时用用户名）。 */
    private String userName;

    /** 发送时间（yyyy-MM-dd HH:mm:ss）。 */
    private String sendTime;
}

package org.example.serviceapi.dto.quizNest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 小组广播事件（quiz-nest -> message，经 Outbox 信封投递）。
 *
 * <p>消费端按 {@code userIds} 逐人写入 {@code notification_center} 站内信。
 * 时间字段用字符串（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code LocalDateTime}：
 * 消费端 {@code EnvelopeCodec} 使用未注册 JavaTimeModule 的 ObjectMapper，
 * 传 {@code LocalDateTime} 会在反序列化阶段失败并被判为毒消息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadCast {

    /**
     * 本次广播的幂等键（UUID），消费端用作 consumed_event.event_id。
     * 每个接收人的收件箱 message_id 为 {@code messageId + ":" + userId}。
     */
    private String messageId;

    /** 小组 ID。 */
    private Long groupId;

    /** 小组名。 */
    private String groupName;

    /** 接收人 ID 列表（生产端已排除发送人，只含 APPROVED 成员）。 */
    private List<Long> userIds;

    /** 发送人 ID。 */
    private Long createUserId;

    /** 发送人展示名（昵称优先，缺失时用用户名）。 */
    private String createUserName;

    /** 广播正文。 */
    private String context;

    /** 发送时间（yyyy-MM-dd HH:mm:ss）。 */
    private String sendTime;
}

package org.example.serviceapi.dto.quizNest;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
/**
 * 小组审批结果站内信（quiz-nest -> message，经 Outbox 信封投递）。
 *
 * <p>生产端在审批事务内发布，消费端写入 {@code notification_center} 站内信。
 * 时间字段统一用字符串（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code LocalDateTime}：
 * 消费端 {@code EnvelopeCodec} 使用未注册 JavaTimeModule 的 ObjectMapper，
 * 传 {@code LocalDateTime} 会在反序列化阶段失败并被判为毒消息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproveResult implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 幂等键，格式 {@code quiz:approve:{uuid}}。
     * 每次审批动作生成新的 UUID；Outbox 重投复用同一条 payload，因此仍然幂等。
     */
    private String messageId;

    /** 小组 ID。 */
    private Long groupId;

    /** 申请人 ID（站内信接收人）。 */
    private Long toUserId;


    /** 审批留言/结果文案，可为空。 */
    private String context;

    /** 小组名。 */
    private String groupName;

    /** 审批人展示名（昵称优先，缺失时用用户名）。 */
    private String userName;

    /** 发送时间（yyyy-MM-dd HH:mm:ss）。 */
    private String sendTime;
}

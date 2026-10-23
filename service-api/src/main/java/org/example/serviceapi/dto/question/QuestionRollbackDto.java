package org.example.serviceapi.dto.question;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 小组自建题目回滚事件（quiz-nest -> question，经 OutboxPro 信封投递）。
 *
 * <p>quiz-nest 同步调用题目服务建题成功后，本地 {@code group_question} 写入失败，
 * 通过该事件通知题目服务删除刚创建的题目，避免题库里留下小组侧没有入口的孤儿题。</p>
 *
 * <p>时间字段用字符串（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code LocalDateTime}：
 * 消费端 {@code EnvelopeCodec} 使用未注册 JavaTimeModule 的 ObjectMapper，
 * 传 {@code LocalDateTime} 会在反序列化阶段失败并被判为毒消息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionRollbackDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待回滚的题目 ID（题目服务已生成的自增主键）。 */
    private Long questionId;

    /** 小组 ID，仅用于日志定位，不参与删除条件。 */
    private Long groupId;

    /** 回滚原因，面向日志而非用户，禁止写入 SQL 或下游原始报错。 */
    private String reason;

    /** 事件发生时间（yyyy-MM-dd HH:mm:ss）。 */
    private String occurredAt;
}

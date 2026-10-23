package org.example.serviceapi.dto.question;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 小组题库清理事件（quiz-nest -> question，经 OutboxPro 信封投递）。
 *
 * <p>小组最后一名成员退出、小组被删除时，quiz-nest 先删本地 {@code group_question} 索引，
 * 再把该组的**自建题目** ID 通过本事件交给题目服务删除；平台题目是共享资源，只删索引，
 * 不会出现在 {@link #questionIds} 中。</p>
 *
 * <p>时间字段用字符串（{@code yyyy-MM-dd HH:mm:ss}）而不是 {@code LocalDateTime}：
 * 消费端 {@code EnvelopeCodec} 使用未注册 JavaTimeModule 的 ObjectMapper。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupQuestionCleanupDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被删除的小组 ID，仅用于日志定位。 */
    private Long groupId;

    /** 需要删除的自建题目 ID 列表（平台题目不在其中）。 */
    private List<Long> questionIds;

    /** 清理原因，面向日志而非用户。 */
    private String reason;

    /** 事件发生时间（yyyy-MM-dd HH:mm:ss）。 */
    private String occurredAt;
}

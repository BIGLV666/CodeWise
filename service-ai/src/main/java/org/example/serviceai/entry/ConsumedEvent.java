package org.example.serviceai.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已消费事件幂等与状态行（consumed_event 表）。
 *
 * <p>以信封 eventId（兜底 messageId）为唯一幂等键，记录 AI 建议事件的
 * 处理状态机：PROCESSING（已认领）→ COMPLETED（通知已发出）或
 * FAILED（重试超限/放弃），result_ref 指向已落库的建议 ai_message 主键。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("consumed_event")
public class ConsumedEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String routingKey;
    private ConsumedEventStatus status;
    private Integer retryCount;
    private String lastError;
    private String resultRef;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

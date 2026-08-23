package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已消费事件幂等与状态记录（consumed_event 表）。
 *
 * <p>以 event_id 作为全局幂等键：业务执行前先插入 PROCESSING 占位行，业务成功转
 * COMPLETED，重试超限转 FAILED；status 以枚举名落库，与 message/ai 侧同构实现保持一致。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("consumed_event")
public class ConsumedEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;//全局唯一事件ID（幂等键）
    private String routingKey;//来源路由键
    private ConsumedEventStatus status;//状态：PROCESSING/COMPLETED/FAILED
    @Builder.Default
    private Integer retryCount = 0;//已失败尝试次数
    private String lastError;//最近一次失败原因（截断到500字符）
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();
    @Builder.Default
    private LocalDateTime updateTime = LocalDateTime.now();
}

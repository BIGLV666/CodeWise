package org.example.servicecommon.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事务性 Outbox 事件实体，对应 {@code event_outbox} 表。
 *
 * <p>生产方在业务事务内写入 PENDING 记录，事务提交后由
 * {@code OutboxRelay} 批量认领投递；投递失败按指数退避重试，
 * 超过上限转 DEAD 等待人工重放。表按库部署（当前为 codewise_question，
 * question 与 judge 共库，两侧写入同一张表）。</p>
 *
 * <p>状态流转：PENDING -投递成功-> SENT；PENDING -失败且未超限-> PENDING(退避)；
 * PENDING -失败且超限-> DEAD；DEAD -人工重放-> PENDING。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("event_outbox")
public class OutboxEvent {

    /** 待投递 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已投递 */
    public static final String STATUS_SENT = "SENT";
    /** 投递超限，等待人工重放 */
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.AUTO)
    private Long outboxId;

    /** 事件唯一 ID（UUID），消费端幂等键 */
    private String eventId;

    /** 事件类型，见 {@code EventTypes} */
    private String eventType;

    /** 目标交换机 */
    private String exchangeName;

    /** 目标路由键 */
    private String routingKey;

    /** EventEnvelope JSON（大字段只放 ID 引用） */
    private String payload;

    /** 生产者服务名 */
    private String producer;

    /** PENDING/SENT/DEAD */
    private String status;

    /** 投递重试次数 */
    private Integer retryCount;

    /** 下次投递时间（指数退避） */
    private LocalDateTime nextRetryTime;

    /** 最近一次投递失败原因（截断至 2000 字符） */
    private String lastError;

    private LocalDateTime createdTime;

    private LocalDateTime sentTime;
}

package org.example.serviceai.entry;

/**
 * 已消费事件状态（consumed_event.status，按枚举名映射入库，与 {@code Message#role} 的 Role 同做法）。
 */
public enum ConsumedEventStatus {
    /** 已认领，业务处理（建议生成/通知发送）尚未全部完成。 */
    PROCESSING,
    /** 处理彻底完成（通知已成功发出）。 */
    COMPLETED,
    /** 终态失败：重试超限死信或主动放弃，留存排查。 */
    FAILED
}

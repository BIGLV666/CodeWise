package org.example.serviceai.entry;

/**
 * ASSISTANT 消息生成状态（ai_message.status）。
 *
 * <p>role 为 USER/SYSTEM 的行 status 恒为 NULL，仅 ASSISTANT 消息使用；
 * 状态收尾必须经 {@code WHERE status='GENERATING'} 条件更新，已完成/已取消
 * 的行不被迟到回调覆盖。</p>
 */
public enum MessageStatus {
    /** 生成中：SSE/同步调用开始前落库的占位行。 */
    GENERATING,
    /** 完成：全文生成成功并已回填 content。 */
    COMPLETED,
    /** 失败：生成中断，保留已生成的部分内容。 */
    FAILED,
    /** 取消：超时或客户端断开而中止，保留已生成的部分内容。 */
    CANCELLED
}

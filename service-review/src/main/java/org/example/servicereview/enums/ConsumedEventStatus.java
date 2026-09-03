package org.example.servicereview.enums;

/**
 * 事件消费状态。
 *
 * <ul>
 *   <li>PROCESSING：业务执行中；消息重投或崩溃恢复后允许重新执行业务；</li>
 *   <li>COMPLETED：业务已成功的终态，唯一跳过态（at-least-once 语义下的去重依据）；</li>
 *   <li>FAILED：重试超限后的失败留存终态，供人工核查与重放。</li>
 * </ul>
 */
public enum ConsumedEventStatus {
    PROCESSING, COMPLETED, FAILED
}

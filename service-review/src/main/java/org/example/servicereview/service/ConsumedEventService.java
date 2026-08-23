package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.servicereview.entry.ConsumedEvent;
import org.example.servicereview.entry.ConsumedEventStatus;
import org.example.servicereview.mapper.ConsumedEventMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 已消费事件（consumed_event）幂等与状态服务。
 *
 * <p>状态语义（at-least-once 消费）：业务执行前先插入 PROCESSING 占位行；COMPLETED
 * 是唯一跳过态；PROCESSING（消费中崩溃残留）与 FAILED（人工重放）在消息重投时都
 * 允许重新执行业务。broker 会对未 ACK 消息重投，崩溃恢复后必须能重新执行业务，
 * 因此除 COMPLETED 外一律放行，可能的重复副作用由下游兼容。</p>
 *
 * <p>review 侧要求 {@link #claim} 与 {@link #complete} 都在业务事务内调用：
 * claim 行与 SM-2 更新同事务提交/回滚——回滚时 claim 行一并消失，重投可重新接管；
 * complete 与业务同提交，避免「业务已提交但幂等行仍是 PROCESSING」导致的重复执行。</p>
 */
@Service
public class ConsumedEventService {

    /** last_error 字段最大长度，超长截断。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private final ConsumedEventMapper consumedEventMapper;

    public ConsumedEventService(ConsumedEventMapper consumedEventMapper) {
        this.consumedEventMapper = consumedEventMapper;
    }

    /**
     * 认领一个事件：首次消费插入 PROCESSING 占位行并返回 NEW；event_id 已存在且为
     * COMPLETED 时返回 DUPLICATE_COMPLETED（调用方应直接 ACK 跳过）；为 PROCESSING
     * 或 FAILED 时返回 RECLAIM（消息重投/人工重放，允许继续执行业务）。
     *
     * @param eventId    全局唯一事件ID（幂等键）
     * @param routingKey 来源路由键，用于追溯
     * @return 认领结果，见 {@link ClaimResult}
     */
    public ClaimResult claim(String eventId, String routingKey) {
        try {
            consumedEventMapper.insert(buildNewEvent(eventId, routingKey));
            return ClaimResult.NEW;
        } catch (DuplicateKeyException duplicateKeyException) {
            ConsumedEvent existing = selectByEventId(eventId);
            if (existing == null) {
                // 唯一键冲突却查不到行（理论不可能）：按新事件重试插入一次，仍冲突则抛出交由调用方重投
                try {
                    consumedEventMapper.insert(buildNewEvent(eventId, routingKey));
                    return ClaimResult.NEW;
                } catch (DuplicateKeyException secondConflict) {
                    throw new IllegalStateException("事件幂等行冲突且无法读取：" + eventId, secondConflict);
                }
            }
            if (existing.getStatus() == ConsumedEventStatus.COMPLETED) {
                return ClaimResult.DUPLICATE_COMPLETED;
            }
            return ClaimResult.RECLAIM;
        }
    }

    /**
     * 业务成功后落终态：status 置为 COMPLETED。必须在业务事务内调用，与业务写入同提交。
     *
     * @param eventId 全局唯一事件ID
     */
    public void complete(String eventId) {
        consumedEventMapper.update(null, new LambdaUpdateWrapper<ConsumedEvent>()
                .eq(ConsumedEvent::getEventId, eventId)
                .set(ConsumedEvent::getStatus, ConsumedEventStatus.COMPLETED));
    }

    /**
     * 记录一次失败：retry_count 原子自增（`retry_count = retry_count + 1`，
     * 多消费者下不丢计数）、last_error 截断到 500 字符留存，
     * 状态保持 PROCESSING 以便消息重投后继续处理。
     *
     * @param eventId 全局唯一事件ID
     * @param error   失败原因（异常描述）
     * @return 更新后的已失败尝试次数（回读；幂等行不存在时返回 0）
     */
    public int recordFailure(String eventId, String error) {
        consumedEventMapper.update(null, new LambdaUpdateWrapper<ConsumedEvent>()
                .eq(ConsumedEvent::getEventId, eventId)
                .setSql("retry_count = retry_count + 1")
                .set(ConsumedEvent::getLastError, abbreviate(error)));
        ConsumedEvent updated = selectByEventId(eventId);
        return updated == null || updated.getRetryCount() == null ? 0 : updated.getRetryCount();
    }

    /**
     * 重试超限后落失败终态：status 置为 FAILED 并留存失败原因，供人工核查后重放
     * （重放 = 将该行 status 改回 PROCESSING 后由生产方重发或人工推送）。
     *
     * @param eventId 全局唯一事件ID
     * @param error   失败原因（异常描述）
     */
    public void markFailed(String eventId, String error) {
        consumedEventMapper.update(null, new LambdaUpdateWrapper<ConsumedEvent>()
                .eq(ConsumedEvent::getEventId, eventId)
                .set(ConsumedEvent::getStatus, ConsumedEventStatus.FAILED)
                .set(ConsumedEvent::getLastError, abbreviate(error)));
    }

    private ConsumedEvent selectByEventId(String eventId) {
        return consumedEventMapper.selectOne(new LambdaQueryWrapper<ConsumedEvent>()
                .eq(ConsumedEvent::getEventId, eventId));
    }

    private ConsumedEvent buildNewEvent(String eventId, String routingKey) {
        return ConsumedEvent.builder()
                .eventId(eventId)
                .routingKey(routingKey)
                .status(ConsumedEventStatus.PROCESSING)
                .retryCount(0)
                .build();
    }

    private String abbreviate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /** 认领结果：NEW 首次认领；RECLAIM 重投/重放后继续处理；DUPLICATE_COMPLETED 已完成，应跳过。 */
    public enum ClaimResult {
        NEW, RECLAIM, DUPLICATE_COMPLETED
    }
}

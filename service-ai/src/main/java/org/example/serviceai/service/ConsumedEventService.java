package org.example.serviceai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.entry.ConsumedEvent;
import org.example.serviceai.entry.ConsumedEventStatus;
import org.example.serviceai.mapper.ConsumedEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 已消费事件（consumed_event）的幂等认领与状态机服务。
 *
 * <p>事件状态机：claim 落 PROCESSING 行（唯一键 event_id 兜底幂等）；
 * 建议落库后 markResultRef 记录结果引用；通知发送成功后 complete 置
 * COMPLETED；每次失败 recordFailure 累加 retry_count；重试超限由死信
 * 处理器 markFailedIfNotCompleted 置终态 FAILED。核心约束：通知发送
 * 成功之前事件绝不定为 COMPLETED。</p>
 */
@Service
@Slf4j
public class ConsumedEventService {

    /** last_error 列长度上限（VARCHAR(500)），超长截断。 */
    private static final int LAST_ERROR_MAX_LEN = 500;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    /**
     * 事件认领结果。
     */
    public enum ClaimOutcome {
        /** 首次认领成功，正常处理。 */
        NEW,
        /** 已完成事件的重复投递，调用方应直接跳过（真重复）。 */
        DUPLICATE_COMPLETED,
        /** 历史行处于 PROCESSING/FAILED，按 at-least-once 语义重新接管继续处理。 */
        RECLAIM
    }

    /**
     * 认领事件：首次插入 PROCESSING 行；唯一键冲突时按既有行状态分派——
     * COMPLETED 视为真重复返回 {@link ClaimOutcome#DUPLICATE_COMPLETED}，
     * PROCESSING/FAILED 视为上次投递未收尾返回 {@link ClaimOutcome#RECLAIM}。
     *
     * @param eventId    全局唯一事件 ID（信封 eventId，兜底 messageId）
     * @param routingKey 来源路由键，仅作留痕
     * @return 认领结果
     */
    public ClaimOutcome claim(String eventId, String routingKey) {
        ConsumedEvent event = ConsumedEvent.builder()
                .eventId(eventId)
                .routingKey(routingKey)
                .status(ConsumedEventStatus.PROCESSING)
                .retryCount(0)
                .build();
        try {
            consumedEventMapper.insert(event);
            return ClaimOutcome.NEW;
        } catch (DuplicateKeyException exception) {
            ConsumedEvent existing = findByEventId(eventId).orElseThrow(() ->
                    new IllegalStateException("事件行存在但查询不到: eventId=" + eventId));
            if (existing.getStatus() == ConsumedEventStatus.COMPLETED) {
                return ClaimOutcome.DUPLICATE_COMPLETED;
            }
            return ClaimOutcome.RECLAIM;
        }
    }

    /**
     * 事件处理彻底完成（通知已成功发出），状态置 COMPLETED。
     *
     * @param eventId 事件 ID
     */
    public void complete(String eventId) {
        consumedEventMapper.update(null, Wrappers.<ConsumedEvent>lambdaUpdate()
                .eq(ConsumedEvent::getEventId, eventId)
                .set(ConsumedEvent::getStatus, ConsumedEventStatus.COMPLETED));
    }

    /**
     * 记录一次失败尝试：retry_count 自增并留存截断后的失败原因。
     *
     * @param eventId 事件 ID
     * @param error   失败原因（超长自动截断）
     * @return 自增后的最新 retry_count
     */
    public int recordFailure(String eventId, String error) {
        consumedEventMapper.update(null, Wrappers.<ConsumedEvent>lambdaUpdate()
                .eq(ConsumedEvent::getEventId, eventId)
                .setSql("retry_count = retry_count + 1")
                .set(ConsumedEvent::getLastError, abbreviate(error)));
        return findByEventId(eventId).map(ConsumedEvent::getRetryCount).orElse(0);
    }

    /**
     * 标记终态失败（无条件覆盖，用于业务主动放弃等场景）。
     *
     * @param eventId 事件 ID
     * @param error   失败原因（超长自动截断）
     */
    public void markFailed(String eventId, String error) {
        consumedEventMapper.update(null, Wrappers.<ConsumedEvent>lambdaUpdate()
                .eq(ConsumedEvent::getEventId, eventId)
                .set(ConsumedEvent::getStatus, ConsumedEventStatus.FAILED)
                .set(ConsumedEvent::getLastError, abbreviate(error)));
    }

    /**
     * 标记终态失败，但不得覆盖已完成事件（死信处理器专用：
     * 仅当状态非 COMPLETED 时更新，避免迟到死信覆盖已完成事件）。
     *
     * @param eventId 事件 ID
     * @param error   失败原因（超长自动截断）
     */
    public void markFailedIfNotCompleted(String eventId, String error) {
        consumedEventMapper.update(null, Wrappers.<ConsumedEvent>lambdaUpdate()
                .eq(ConsumedEvent::getEventId, eventId)
                .ne(ConsumedEvent::getStatus, ConsumedEventStatus.COMPLETED)
                .set(ConsumedEvent::getStatus, ConsumedEventStatus.FAILED)
                .set(ConsumedEvent::getLastError, abbreviate(error)));
    }

    /**
     * 记录业务结果引用（建议消息已落库的 ai_message 主键），通知失败重试时
     * 依据该引用跳过重复生成、仅重发通知。
     *
     * @param eventId   事件 ID
     * @param resultRef 已生成的建议 ai_message 主键
     */
    public void markResultRef(String eventId, Long resultRef) {
        consumedEventMapper.update(null, Wrappers.<ConsumedEvent>lambdaUpdate()
                .eq(ConsumedEvent::getEventId, eventId)
                .set(ConsumedEvent::getResultRef, resultRef == null ? null : resultRef.toString()));
    }

    /**
     * 按事件 ID查询已消费事件行。
     *
     * @param eventId 事件 ID
     * @return 事件行（可能为空）
     */
    public Optional<ConsumedEvent> findByEventId(String eventId) {
        return Optional.ofNullable(consumedEventMapper.selectOne(
                Wrappers.<ConsumedEvent>lambdaQuery().eq(ConsumedEvent::getEventId, eventId)));
    }

    /** 失败原因截断到 last_error 列长度上限。 */
    private String abbreviate(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX_LEN) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX_LEN);
    }
}

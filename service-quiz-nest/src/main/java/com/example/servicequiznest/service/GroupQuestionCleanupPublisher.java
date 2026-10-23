package com.example.servicequiznest.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.GroupQuestionCleanupDto;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 小组题库清理事件发布器。
 *
 * <p>使用 {@link Propagation#MANDATORY}：清理事件必须与「删除小组 + 删除本地索引」在
 * 同一个事务里，删组回滚时事件也不能投递；如果调用方没有事务，直接失败暴露调用错误，
 * 而不是悄悄发一条「组还在、题被删」的消息。</p>
 */
@Component
public class GroupQuestionCleanupPublisher {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OutboxProPublisher outboxProPublisher;

    public GroupQuestionCleanupPublisher(OutboxProPublisher outboxProPublisher) {
        this.outboxProPublisher = outboxProPublisher;
    }

    /**
     * 发布自建题目清理事件。
     *
     * @param groupId     已删除的小组 id
     * @param questionIds 需要删除的自建题目 id（平台题目不在其中）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Long groupId, List<Long> questionIds) {
        outboxProPublisher.publish(EventTypes.GROUP_QUESTION_CLEANUP, GroupQuestionCleanupDto.builder()
                .groupId(groupId)
                .questionIds(List.copyOf(questionIds))
                .reason("小组已无成员，清理自建题目")
                .occurredAt(LocalDateTime.now().format(TIME_FORMATTER))
                .build());
    }
}

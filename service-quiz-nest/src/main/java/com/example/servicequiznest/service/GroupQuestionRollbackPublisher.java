package com.example.servicequiznest.service;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 小组自建题目回滚事件发布器。
 *
 * <p>必须使用 {@link Propagation#REQUIRES_NEW}：本地 {@code group_question} 写入失败时，
 * 外层事务（如果存在）已被标记 rollback-only，复用外层事务会让回滚事件一起回滚，
 * 题目服务永远收不到补偿。独立事务只写 outbox 一行，提交后由 Relay 至少一次投递。</p>
 */
@Component
public class GroupQuestionRollbackPublisher {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OutboxProPublisher outboxProPublisher;

    public GroupQuestionRollbackPublisher(OutboxProPublisher outboxProPublisher) {
        this.outboxProPublisher = outboxProPublisher;
    }

    /**
     * 发布题目回滚事件。
     *
     * @param questionId 题目服务已创建成功的题目 id
     * @param groupId    小组 id，仅用于消费端日志定位
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(Long questionId, Long groupId) {
        outboxProPublisher.publish(EventTypes.QUESTION_CREATE_ROLLBACK, QuestionRollbackDto.builder()
                .questionId(questionId)
                .groupId(groupId)
                .reason("本地小组题目关联写入失败")
                .occurredAt(LocalDateTime.now().format(TIME_FORMATTER))
                .build());
    }
}

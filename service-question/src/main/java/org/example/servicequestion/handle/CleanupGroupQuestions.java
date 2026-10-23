package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.GroupQuestionCleanupDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.MQ.MessageAcknowledgements;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.service.QuestionService;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * 小组题库清理处理器（{@code question.group.cleanup.routing}，手动 ACK）。
 *
 * <p>链路：小组最后一名成员退出、小组被删除时，quiz-nest 在同一事务里删除本地
 * {@code group_question} 索引并经 OutboxPro 发布 {@code GROUP_QUESTION_CLEANUP}；
 * 本处理器只负责删除 payload 中的自建题目（平台题目不在其中）。</p>
 *
 * <p>幂等：按 id 批量删除，题目已不存在返回 0 行也 ACK；payload 里没有题目 ID
 * （组里只有平台题）直接 ACK。消息体非法视为毒消息丢弃；数据库异常记录 ERROR 后
 * 丢弃（{@code question.queue} 未挂 DLX），日志带 groupId 供人工清理。</p>
 */
@Component
@Slf4j
public class CleanupGroupQuestions implements MessageHandler {

    private final QuestionService questionService;

    public CleanupGroupQuestions(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_GROUP_CLEANUP_ROUTING_KEY;
    }

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        GroupQuestionCleanupDto cleanup;
        try {
            cleanup = EnvelopeCodec.unwrap(messageBody, GroupQuestionCleanupDto.class);
        } catch (Exception e) {
            log.error("小组题库清理消息格式错误，消息体：{}", messageBody, e);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }
        if (cleanup == null) {
            log.error("小组题库清理消息为空，消息体：{}", messageBody);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }

        if (cleanup.getQuestionIds() == null || cleanup.getQuestionIds().isEmpty()) {
            log.info("小组题库无自建题目，无需清理: groupId={}", cleanup.getGroupId());
            MessageAcknowledgements.ack(channel, deliveryTag);
            return;
        }

        try {
            int deleted = questionService.deleteQuestionsBatch(cleanup.getQuestionIds());
            log.info("小组题库清理完成: groupId={}, requested={}, deleted={}, reason={}",
                    cleanup.getGroupId(), cleanup.getQuestionIds().size(), deleted, cleanup.getReason());
            MessageAcknowledgements.ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("小组题库清理失败，需人工删除题目: groupId={}, questionIds={}",
                    cleanup.getGroupId(), cleanup.getQuestionIds(), e);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
        }
    }
}

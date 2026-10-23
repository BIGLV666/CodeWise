package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionRollbackDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.MQ.MessageAcknowledgements;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.service.QuestionService;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * 小组自建题目回滚处理器（{@code question.rollback.routing}，手动 ACK）。
 *
 * <p>链路：quiz-nest 同步建题成功、但本地 {@code group_question} 写入失败时，
 * 在独立事务里经 OutboxPro 发布 {@code QUESTION_CREATE_ROLLBACK}；本处理器按
 * questionId 删除刚创建的题目，避免题库留下小组侧没有入口的孤儿题。</p>
 *
 * <p>幂等：按 id 删除，题目已不存在（重复投递、人工清理）返回 0 行同样 ACK。
 * 消息体非法视为毒消息丢弃；删除时数据库异常记录 ERROR 后丢弃（{@code question.queue}
 * 未挂 DLX，无法自动重试），日志带 questionId 供人工清理。</p>
 */
@Component
@Slf4j
public class RollbackQuestion implements MessageHandler {

    private final QuestionService questionService;

    public RollbackQuestion(QuestionService questionService) {
        this.questionService = questionService;
    }

    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_ROLLBACK_ROUTING_KEY;
    }

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        QuestionRollbackDto rollback;
        try {
            // 信封/裸格式双读：生产端经 OutboxPro 投递为统一信封
            rollback = EnvelopeCodec.unwrap(messageBody, QuestionRollbackDto.class);
        } catch (Exception e) {
            log.error("题目回滚消息格式错误，消息体：{}", messageBody, e);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }
        if (rollback == null || rollback.getQuestionId() == null) {
            log.error("题目回滚消息缺少 questionId，消息体：{}", messageBody);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }

        try {
            boolean deleted = questionService.rollbackCreatedQuestion(rollback.getQuestionId());
            log.info("小组自建题目回滚完成: questionId={}, groupId={}, deleted={}, reason={}",
                    rollback.getQuestionId(), rollback.getGroupId(), deleted, rollback.getReason());
            MessageAcknowledgements.ack(channel, deliveryTag);
        } catch (Exception e) {
            log.error("小组自建题目回滚失败，需人工删除题目: questionId={}, groupId={}",
                    rollback.getQuestionId(), rollback.getGroupId(), e);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
        }
    }
}

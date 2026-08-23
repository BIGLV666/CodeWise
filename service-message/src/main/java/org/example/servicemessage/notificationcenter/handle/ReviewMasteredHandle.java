package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.ReviewMasteredDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.mq.MessageHandler;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.websocket.websocketService.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 复习掌握祝贺处理器（notification.review.mastered.routing，手动 ACK）。
 *
 * <p>整条链路：service-review 在 SM-2 更新事务内检测到复习计划由 学习中(0) 首次转为
 * 已掌握(1)，经事务性 Outbox 发布 {@code REVIEW_MASTERED} 事件（payload 为
 * {@link ReviewMasteredDto}，题目名生产端不填）；本处理器消费后经 Feign 异步拉取
 * 题目名，写入通知中心收件箱（notification_center），再经 WebSocket
 * （INBOX_REVIEW 队列）尽力推送祝贺。</p>
 *
 * <p>幂等语义：以 messageId（{@code review:mastered:{userId}:{questionId}}，掌握是
 * 终态、天然唯一）走 consumed_event 数据库幂等——业务执行前先 claim 占位
 * （PROCESSING），收件箱落库成功后置 COMPLETED 再 ACK。COMPLETED 是唯一跳过态；
 * PROCESSING/FAILED 在消息重投时重新执行业务。收件箱 message_id 唯一键冲突
 * 同样视为已完成（complete + ACK）。</p>
 *
 * <p>失败处置：Feign 拉取题目名或入库失败按 retry_count 计数，不足 {@value
 * MAX_RETRY_COUNT} 次 nack 重投立即重试；达到后置 FAILED 留存（人工重放 =
 * 将 consumed_event 该行 status 改回 PROCESSING 后由生产方重发或人工推送）
 * 再 nack 丢弃。WebSocket 推送为尽力而为，失败仅告警不影响 ACK（收件箱才是
 * 持久收端）。消息体非法（非 JSON / 缺 messageId/userId/questionId）视为毒消息
 * 直接 nack 丢弃。</p>
 */
@Component
@Slf4j
public class ReviewMasteredHandle implements MessageHandler {
    /** 最大立即重试次数，达到后失败留存并丢弃。 */
    private static final int MAX_RETRY_COUNT = 3;
    /** 加入时间展示取日期部分（yyyy-MM-dd HH:mm:ss 的前 10 位） */
    private static final int JOIN_DATE_LENGTH = 10;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConsumedEventService consumedEventService;
    @Autowired
    private QuestionFeignClient questionFeignClient;
    @Autowired
    private NotificationCenterMapper notificationCenterMapper;
    @Autowired
    private WebSocketPushService webSocketPushService;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_REVIEW_MASTERED_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        ReviewMasteredDto masteredDto;
        try {
            // 信封/裸格式双读：生产端经 Outbox 投递为统一信封（payload 为 ReviewMasteredDto）
            masteredDto = EnvelopeCodec.unwrap(message, ReviewMasteredDto.class);
            validate(masteredDto);
        } catch (Exception e) {
            log.error("复习掌握消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String messageId = masteredDto.getMessageId();
        if (consumedEventService.claim(messageId, getRoutingKey())
                == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("复习掌握消息已消费完成，幂等跳过: messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            masteredDto.setQuestionTitle(fetchQuestionTitle(masteredDto.getQuestionId()));
            insertNotification(masteredDto);
            try {
                webSocketPushService.pushToUserQueue(
                        masteredDto.getUserId(), WebSocketQueueName.INBOX_REVIEW.name(), masteredDto);
            } catch (Exception pushException) {
                log.warn("复习掌握祝贺已入库，但 WebSocket 推送失败，messageId={}", messageId, pushException);
            }
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicateKeyException) {
            // notification_center.message_id 唯一键冲突 = 祝贺通知已入库，视为已完成
            log.info("复习掌握祝贺通知已存在，幂等完成: messageId={}", messageId);
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleFailure(messageId, e, deliveryTag, channel);
        }
    }

    /**
     * 失败后的统一处置：不足 {@value MAX_RETRY_COUNT} 次 nack 重投立即重试；
     * 达到后置 FAILED 留存并 nack 丢弃。
     */
    private void handleFailure(String messageId, Exception e, long deliveryTag, Channel channel)
            throws IOException {
        int retryCount = consumedEventService.recordFailure(messageId, e.toString());
        if (retryCount < MAX_RETRY_COUNT) {
            log.warn("复习掌握祝贺处理失败，重投重试: messageId={}, retryCount={}",
                    messageId, retryCount, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        consumedEventService.markFailed(messageId, e.toString());
        log.error("复习掌握祝贺处理失败 {} 次，标记 FAILED 留存人工重放并丢弃: messageId={}",
                MAX_RETRY_COUNT, messageId, e);
        channel.basicNack(deliveryTag, false, false);
    }

    /** 校验幂等键与业务主键，缺失即毒消息。 */
    private void validate(ReviewMasteredDto dto) {
        if (dto == null || dto.getMessageId() == null || dto.getMessageId().isBlank()
                || dto.getUserId() == null || dto.getQuestionId() == null) {
            throw new IllegalArgumentException("复习掌握消息缺少必要字段");
        }
    }

    /**
     * 经 Feign 拉取题目名（生产端不填，由本端异步补齐）。
     *
     * @throws IllegalStateException service-question 不可用或题目不存在时抛出，交由统一失败处置重试
     */
    private String fetchQuestionTitle(Long questionId) {
        Result<QuestionDto> result = questionFeignClient.getQuestionInfo(questionId);
        if (result == null || !Integer.valueOf(200).equals(result.getCode()) || result.getData() == null) {
            throw new IllegalStateException("获取题目信息失败: questionId=" + questionId);
        }
        return result.getData().getTitle();
    }

    /**
     * 组装修贺通知并写入通知中心收件箱。
     * <p>type=REVIEW_MASTERED、businessType=REVIEW_RECORD、businessId=questionId；
     * extraData 存放补齐题目名后的 {@link ReviewMasteredDto} JSON 供前端渲染。</p>
     */
    private void insertNotification(ReviewMasteredDto dto) {
        NotificationCenter notification = new NotificationCenter();
        notification.setMessageId(dto.getMessageId());
        notification.setType(NotificationCenterType.REVIEW_MASTERED);
        notification.setBusinessType(BusinessType.REVIEW_RECORD);
        notification.setBusinessId(dto.getQuestionId());
        notification.setUserId(dto.getUserId());
        notification.setTitle("复习掌握祝贺");
        notification.setContent(buildContent(dto));
        notification.setExtraData(jsonOf(dto));
        notificationCenterMapper.insert(notification);
    }

    /** 组装祝贺文案：包含题目名、累计复习次数与加入复习计划的日期。 */
    private String buildContent(ReviewMasteredDto dto) {
        String joinDate = dto.getJoinTime() != null && dto.getJoinTime().length() >= JOIN_DATE_LENGTH
                ? dto.getJoinTime().substring(0, JOIN_DATE_LENGTH) : null;
        String joinPhrase = joinDate == null
                ? "从加入复习计划以来坚持到现在"
                : "从 " + joinDate + " 开始坚持到现在";
        return "🎉 恭喜！你已掌握《" + dto.getQuestionTitle() + "》，共复习 "
                + (dto.getTotalReviewCount() == null ? 0 : dto.getTotalReviewCount()) + " 次。"
                + joinPhrase + "，继续保持！";
    }

    private String jsonOf(ReviewMasteredDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception exception) {
            throw new IllegalStateException("复习掌握事件序列化失败", exception);
        }
    }
}

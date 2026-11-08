package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.quizNest.ApproveResult;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicemessage.consumedevent.service.ConsumedEventService;
import org.example.servicemessage.exception.MessageErrorCode;
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
 * 小组审批结果处理器（notification.approve.result.routing，手动 ACK）。
 *
 * <p>链路：service-quiz-nest 在审批事务内把成员状态迁移为 APPROVED/REJECTED，
 * 同时经 OutboxPro 发布 {@code GROUP_APPROVAL_RESULT} 事件（payload 为
 * {@link ApproveResult}）；本处理器消费后写入 notification_center 站内信，
 * 再经 WebSocket（{@link WebSocketQueueName#INBOX_GROUP_APPROVAL_RESULT}）尽力推送。</p>
 *
 * <p>幂等：以 payload 里的 {@code messageId}（{@code quiz:approve:{uuid}}）走
 * consumed_event 状态机。COMPLETED 是唯一跳过态；PROCESSING/FAILED 在消息重投时
 * 重新执行业务；收件箱 message_id 唯一键冲突同样视为已完成。</p>
 */
@Component
@Slf4j
public class GroupApproveResultHandle implements MessageHandler {

    /** 最大立即重试次数，达到后失败留存并丢弃。 */
    private static final int MAX_RETRY_COUNT = 3;
    /** notification_center.message_id / consumed_event.event_id 的列宽。 */
    private static final int MESSAGE_ID_MAX_LENGTH = 64;
    private static final String DEFAULT_GROUP = "该小组";
    private static final String DEFAULT_CONTEXT = "审批结果已更新，点击查看详情。";

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ConsumedEventService consumedEventService;
    @Autowired
    private NotificationCenterMapper notificationCenterMapper;
    @Autowired
    private WebSocketPushService webSocketPushService;

    @Override
    public String getRoutingKey() {
        return MqContexts.NOTIFICATION_APPROVE_RESULT_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        ApproveResult result;
        try {
            // 信封/裸格式双读：生产端经 OutboxPro 投递为统一信封
            result = EnvelopeCodec.unwrap(message, ApproveResult.class);
            validate(result);
        } catch (Exception e) {
            log.error("小组审批结果消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String messageId = result.getMessageId();
        if (consumedEventService.claim(messageId, getRoutingKey())
                == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("小组审批结果消息已消费完成，幂等跳过: messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            insertNotification(result);
            try {
                webSocketPushService.pushToUserQueue(
                        result.getToUserId(),
                        WebSocketQueueName.INBOX_GROUP_APPROVAL_RESULT.name(),
                        result);
            } catch (Exception pushException) {
                // 收件箱才是持久收端，推送失败只告警，不影响 ACK
                log.warn("小组审批结果已入库，但 WebSocket 推送失败，messageId={}", messageId, pushException);
            }
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicateKeyException) {
            // notification_center.message_id 唯一键冲突 = 该审批通知已入库
            log.info("小组审批结果通知已存在，幂等完成: messageId={}", messageId);
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
            log.warn("小组审批结果处理失败，重投重试: messageId={}, retryCount={}", messageId, retryCount, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        consumedEventService.markFailed(messageId, e.toString());
        log.error("小组审批结果处理失败 {} 次，标记 FAILED 留存人工重放并丢弃: messageId={}",
                MAX_RETRY_COUNT, messageId, e);
        channel.basicNack(deliveryTag, false, false);
    }

    /** 校验幂等键与业务主键，缺失即毒消息。 */
    private void validate(ApproveResult dto) {
        if (dto == null || isBlank(dto.getMessageId())
                || dto.getGroupId() == null || dto.getToUserId() == null) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
        // 超长 messageId 落库必失败，提前当毒消息丢弃，避免无意义的 3 次重试
        if (dto.getMessageId().length() > MESSAGE_ID_MAX_LENGTH) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
    }

    /**
     * 组装修理审批结果通知并写入通知中心收件箱。
     * <p>type=GROUP_APPROVAL_RESULT、businessType=GROUP、businessId=groupId；
     * extraData 存放 {@link ApproveResult} JSON 供前端渲染。</p>
     */
    private void insertNotification(ApproveResult dto) {
        NotificationCenter notification = new NotificationCenter();
        notification.setMessageId(dto.getMessageId());
        notification.setType(NotificationCenterType.GROUP_APPROVAL_RESULT);
        notification.setBusinessType(BusinessType.GROUP);
        notification.setBusinessId(dto.getGroupId());
        notification.setUserId(dto.getToUserId());
        notification.setTitle("小组审批结果");
        notification.setContent(buildContent(dto));
        notification.setExtraData(jsonOf(dto));
        notificationCenterMapper.insert(notification);
    }

    /** 组装审批结果文案：小组名 + 生产端给出的结果文案。 */
    private String buildContent(ApproveResult dto) {
        String group = isBlank(dto.getGroupName()) ? DEFAULT_GROUP : "《" + dto.getGroupName() + "》";
        String context = isBlank(dto.getContext()) ? DEFAULT_CONTEXT : dto.getContext().trim();
        return group + context;
    }

    private String jsonOf(ApproveResult dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception exception) {
            throw new BizException(MessageErrorCode.GROUP_NOTIFICATION_SERIALIZE_FAILED, exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.quizNest.BroadCast;
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
import java.util.LinkedHashSet;

/**
 * 小组广播处理器（notification.broadcast.routing，手动 ACK）。
 *
 * <p>链路：service-quiz-nest 的管理员发送广播，经 OutboxPro 发布
 * {@code GROUP_BROADCAST} 事件（payload 为 {@link BroadCast}，接收人只含 APPROVED
 * 且排除发送人）；本处理器为每个接收人写入一条 notification_center 站内信，
 * 再经 WebSocket（{@link WebSocketQueueName#INBOX_GROUP_BROADCAST}）尽力推送。</p>
 *
 * <p>幂等：用广播的 {@code messageId}（UUID）走 consumed_event 状态机；每个接收人的
 * 收件箱 message_id 为 {@code messageId + ":" + userId}。重复投递时唯一键冲突的接收人
 * 直接跳过，未写入的接收人继续补齐，因此部分失败后重试不会产生重复站内信。</p>
 */
@Component
@Slf4j
public class GroupBroadcastHandle implements MessageHandler {

    /** 最大立即重试次数，达到后失败留存并丢弃。 */
    private static final int MAX_RETRY_COUNT = 3;
    /** notification_center.message_id / consumed_event.event_id 的列宽。 */
    private static final int MESSAGE_ID_MAX_LENGTH = 64;
    /**
     * 广播 messageId 最大长度：给 {@code ":{userId}"} 后缀留空间
     * （BIGINT 最多 19 位，44 + 1 + 19 = 64）。生产端使用 36 位 UUID。
     */
    private static final int BROADCAST_ID_MAX_LENGTH = 44;
    private static final String DEFAULT_GROUP = "小组";
    private static final String DEFAULT_SENDER = "管理员";

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
        return MqContexts.NOTIFICATION_BROADCAST_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        BroadCast broadCast;
        try {
            // 信封/裸格式双读：生产端经 OutboxPro 投递为统一信封
            broadCast = EnvelopeCodec.unwrap(message, BroadCast.class);
            validate(broadCast);
        } catch (Exception e) {
            log.error("小组广播消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String messageId = broadCast.getMessageId();
        if (consumedEventService.claim(messageId, getRoutingKey())
                == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("小组广播消息已消费完成，幂等跳过: messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // 去重后再写收件箱：同一用户重复出现在列表里也只发一封
            for (Long userId : new LinkedHashSet<>(broadCast.getUserIds())) {
                if (!insertNotificationIfAbsent(broadCast, userId)) {
                    continue;
                }
                // 只有本次真正入库的接收人才推送，重投补齐时不会重复推送
                try {
                    webSocketPushService.pushToUserQueue(
                            userId, WebSocketQueueName.INBOX_GROUP_BROADCAST.name(), broadCast);
                } catch (Exception pushException) {
                    log.warn("小组广播已入库，但 WebSocket 推送失败，messageId={}, userId={}",
                            messageId, userId, pushException);
                }
            }
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleFailure(messageId, e, deliveryTag, channel);
        }
    }

    /** 写单个接收人的收件箱；唯一键冲突说明上次投递已写入，返回 false 跳过推送。 */
    private boolean insertNotificationIfAbsent(BroadCast dto, Long userId) {
        try {
            insertNotification(dto, userId);
            return true;
        } catch (DuplicateKeyException duplicateKeyException) {
            log.info("小组广播通知已存在，跳过该接收人: messageId={}, userId={}",
                    dto.getMessageId(), userId);
            return false;
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
            log.warn("小组广播处理失败，重投重试: messageId={}, retryCount={}", messageId, retryCount, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        consumedEventService.markFailed(messageId, e.toString());
        log.error("小组广播处理失败 {} 次，标记 FAILED 留存人工重放并丢弃: messageId={}",
                MAX_RETRY_COUNT, messageId, e);
        channel.basicNack(deliveryTag, false, false);
    }

    /** 校验幂等键、接收人与正文，缺失即毒消息。 */
    private void validate(BroadCast dto) {
        if (dto == null || isBlank(dto.getMessageId())
                || dto.getGroupId() == null
                || dto.getUserIds() == null || dto.getUserIds().isEmpty()
                || isBlank(dto.getContext())) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
        if (dto.getMessageId().length() > BROADCAST_ID_MAX_LENGTH) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
        if (dto.getUserIds().stream().anyMatch(userId -> userId == null)) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
    }

    /**
     * 组装广播并写入某个接收人的收件箱。
     * <p>type=GROUP_BROADCAST、businessType=GROUP、businessId=groupId；
     * extraData 存放 {@link BroadCast} JSON 供前端渲染。</p>
     */
    private void insertNotification(BroadCast dto, Long userId) {
        NotificationCenter notification = new NotificationCenter();
        notification.setMessageId(dto.getMessageId() + ":" + userId);
        notification.setType(NotificationCenterType.GROUP_BROADCAST);
        notification.setBusinessType(BusinessType.GROUP);
        notification.setBusinessId(dto.getGroupId());
        notification.setUserId(userId);
        notification.setTitle("小组广播");
        notification.setContent(buildContent(dto));
        notification.setExtraData(jsonOf(dto));
        notificationCenterMapper.insert(notification);
    }

    /** 组装广播文案：小组名 + 发送人 + 正文。 */
    private String buildContent(BroadCast dto) {
        String group = isBlank(dto.getGroupName()) ? DEFAULT_GROUP : "《" + dto.getGroupName() + "》";
        String sender = isBlank(dto.getCreateUserName()) ? DEFAULT_SENDER : dto.getCreateUserName();
        return group + sender + "：" + dto.getContext().trim();
    }

    private String jsonOf(BroadCast dto) {
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

package org.example.servicemessage.notificationcenter.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.quizNest.JoinToken;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.WebSocketQueueName;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.RedisDto.RedisContext;
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
 * 小组邀请码处理器（notification.join.token.routing，手动 ACK）。
 *
 * <p>链路：service-quiz-nest 在发邀请时把 6 位邀请码写入 Redis，并在事务内通过
 * OutboxPro 发布 {@code GROUP_JOIN_TOKEN} 事件（payload 为 {@link JoinToken}）；
 * 本处理器消费后写入 notification_center 站内信，再经 WebSocket
 * （{@link WebSocketQueueName#INBOX_GROUP_INVITE} 队列）尽力推送。</p>
 *
 * <p>幂等：以 payload 里的 {@code messageId}（{@code quiz:join-token:groupId:toUserId:token}）
 * 走 consumed_event 状态机。COMPLETED 是唯一跳过态；PROCESSING/FAILED 在消息重投时
 * 重新执行业务（at-least-once），收件箱 message_id 唯一键冲突同样视为已完成。
 * 重新发送邀请会生成新邀请码 → 新 messageId → 一封新站内信，这是预期行为。</p>
 *
 * <p>失败处置：入库/推送失败按 retry_count 计数，不足 {@value MAX_RETRY_COUNT} 次
 * nack 重投立即重试；达到后置 FAILED 留存（人工重放 = 将该行 status 改回 PROCESSING
 * 后由生产方重发）再 nack 丢弃。消息体非法（非 JSON / 缺 messageId/token/groupId/
 * toUserId）视为毒消息直接丢弃。</p>
 */
@Component
@Slf4j
public class JoinTokenHandle implements MessageHandler {

    /** 最大立即重试次数，达到后失败留存并丢弃。 */
    private static final int MAX_RETRY_COUNT = 3;
    /** notification_center.message_id / consumed_event.event_id 的列宽。 */
    private static final int MESSAGE_ID_MAX_LENGTH = 64;
    private static final String DEFAULT_INVITER = "小组管理员";
    private static final String DEFAULT_GROUP = "该小组";

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
        return MqContexts.NOTIFICATION_JOIN_TOKEN_ROUTING_KEY;
    }

    @Override
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        JoinToken joinToken;
        try {
            // 信封/裸格式双读：生产端经 OutboxPro 投递为统一信封
            joinToken = EnvelopeCodec.unwrap(message, JoinToken.class);
            validate(joinToken);
        } catch (Exception e) {
            log.error("小组邀请消息格式错误，消息体：{}", message, e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String messageId = joinToken.getMessageId();
        if (consumedEventService.claim(messageId, getRoutingKey())
                == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("小组邀请消息已消费完成，幂等跳过: messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            insertNotification(joinToken);
            try {
                webSocketPushService.pushToUserQueue(
                        joinToken.getToUserId(), WebSocketQueueName.INBOX_GROUP_INVITE.name(), joinToken);
            } catch (Exception pushException) {
                // 收件箱才是持久收端，推送失败只告警，不影响 ACK
                log.warn("小组邀请已入库，但 WebSocket 推送失败，messageId={}", messageId, pushException);
            }
            consumedEventService.complete(messageId);
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException duplicateKeyException) {
            // notification_center.message_id 唯一键冲突 = 该邀请通知已入库
            log.info("小组邀请通知已存在，幂等完成: messageId={}", messageId);
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
            log.warn("小组邀请处理失败，重投重试: messageId={}, retryCount={}", messageId, retryCount, e);
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        consumedEventService.markFailed(messageId, e.toString());
        log.error("小组邀请处理失败 {} 次，标记 FAILED 留存人工重放并丢弃: messageId={}",
                MAX_RETRY_COUNT, messageId, e);
        channel.basicNack(deliveryTag, false, false);
    }

    /** 校验幂等键与业务主键，缺失即毒消息。 */
    private void validate(JoinToken dto) {
        if (dto == null || isBlank(dto.getMessageId()) || isBlank(dto.getToken())
                || dto.getGroupId() == null || dto.getToUserId() == null) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
        // 超长 messageId 落库必失败，提前当毒消息丢弃，避免无意义的 3 次重试
        if (dto.getMessageId().length() > MESSAGE_ID_MAX_LENGTH) {
            throw new BizException(MessageErrorCode.NOTIFICATION_PAYLOAD_INCOMPLETE);
        }
    }

    /**
     * 组装修邀通知并写入通知中心收件箱。
     * <p>type=GROUP_INVITE、businessType=GROUP、businessId=groupId；
     * extraData 存放 {@link JoinToken} JSON（含邀请码），供前端渲染邀请卡片。</p>
     */
    private void insertNotification(JoinToken dto) {
        NotificationCenter notification = new NotificationCenter();
        notification.setMessageId(dto.getMessageId());
        notification.setType(NotificationCenterType.GROUP_INVITE);
        notification.setBusinessType(BusinessType.GROUP);
        notification.setBusinessId(dto.getGroupId());
        notification.setUserId(dto.getToUserId());
        notification.setTitle("小组邀请");
        notification.setContent(buildContent(dto));
        notification.setExtraData(jsonOf(dto));
        notificationCenterMapper.insert(notification);
    }

    /** 组装邀请文案：邀请人 + 小组名 + 留言 + 邀请码 + 有效期。 */
    private String buildContent(JoinToken dto) {
        String inviter = isBlank(dto.getUserName()) ? DEFAULT_INVITER : dto.getUserName();
        String group = isBlank(dto.getGroupName()) ? DEFAULT_GROUP : "《" + dto.getGroupName() + "》";
        StringBuilder content = new StringBuilder()
                .append(inviter).append(" 邀请你加入").append(group);
        if (!isBlank(dto.getContext())) {
            content.append("：").append(dto.getContext().trim());
        }
        long validDays = RedisContext.JOIN_GROUP_TOKEN_TTL_SECONDS / (24 * 3600);
        content.append("。邀请码：").append(dto.getToken())
                .append("，").append(validDays).append(" 天内有效。");
        return content.toString();
    }

    private String jsonOf(JoinToken dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception exception) {
            throw new BizException(MessageErrorCode.GROUP_INVITE_SERIALIZE_FAILED, exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

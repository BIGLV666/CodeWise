package org.example.servicejudge.Mq.handler;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventEnvelope;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 判题死信处理器（judge.dead.queue，手动 ACK）。
 *
 * <p>按消息类型分派：JUDGE_SUBMIT_REQUEST（或历史裸 Long）-> 登记
 * failure_submit 并把提交状态置为 failure，后续由管理员接口或补偿流程
 * 投递重试；JUDGE_RETRY_REQUEST（payload 为 failureId）-> 状态已由
 * RetryHandler 落库，只 log.warn 留痕，不得把 failureId 当 submitRecordId
 * 插表；其他类型 -> 记录日志后放行。</p>
 */
@Slf4j
@Service
public class JudgeDeadLetterHandler {

    private final SubmitRecordMapper submitRecordMapper;
    private final FailureSubmitMapper failureSubmitMapper;

    /** 代理自注入：保证登记逻辑的事务经代理生效（ACK 在事务提交之后执行）。 */
    @Autowired
    @Lazy
    private JudgeDeadLetterHandler self;

    public JudgeDeadLetterHandler(
            SubmitRecordMapper submitRecordMapper,
            FailureSubmitMapper failureSubmitMapper
    ) {
        this.submitRecordMapper = submitRecordMapper;
        this.failureSubmitMapper = failureSubmitMapper;
    }

    /**
     * 消费一条判题死信消息。
     *
     * @param message 原始 AMQP 消息（body 为信封或历史裸格式）
     * @param channel 当前 channel，用于手动 ACK/NACK
     * @throws IOException ACK/NACK 失败（交还容器处理，消息重新投递）
     */
    @RabbitListener(queues = MqContexts.JUDGE_DLQ)
    public void consumeDeadMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        Long submitRecordId = resolveSubmitRecordId(body);
        if (submitRecordId == null) {
            // 重试死信/未知类型：只留痕，正常放行
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            self.registerFailure(submitRecordId);
            log.error("判题消息进入死信队列，submitId={}, death={}",
                    submitRecordId, message.getMessageProperties().getHeader("x-death"));
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException exception) {
            // 死信消息重复消费：failure_submit 已登记，幂等放行
            log.info("死信消息重复消费，submitId={}", submitRecordId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("死信登记失败，放回死信队列丢弃: submitId={}", submitRecordId, exception);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 解析死信消息应登记的 submitRecordId。
     *
     * @param body 消息体字符串
     * @return 提交记录 ID；重试死信/未知类型/无法解析的非数值裸消息返回 null（放行）
     */
    private Long resolveSubmitRecordId(String body) {
        if (EnvelopeCodec.isEnvelope(body)) {
            EventEnvelope envelope = EnvelopeCodec.readEnvelope(body);
            String eventType = envelope.getEventType();
            if (EventTypes.JUDGE_SUBMIT_REQUEST.equals(eventType)) {
                return EnvelopeCodec.unwrap(body, Long.class);
            }
            if (EventTypes.JUDGE_RETRY_REQUEST.equals(eventType)) {
                // 状态已由 JudgeRetryHandler 落库（failure_submit=FAILURE），仅留痕
                log.warn("重试消息进入死信，仅留痕: failureId={}, eventId={}",
                        envelope.getPayload(), envelope.getEventId());
                return null;
            }
            log.warn("未知类型的死信消息，放行: eventType={}, eventId={}", eventType, envelope.getEventId());
            return null;
        }
        // 历史裸格式：提交死信为 JSON 数值 submitRecordId；解析失败按未知类型放行
        try {
            return EnvelopeCodec.unwrap(body, Long.class);
        } catch (IllegalArgumentException exception) {
            log.warn("无法解析的裸格式死信消息，放行: body={}", abbreviate(body));
            return null;
        }
    }

    /**
     * 登记失败提交并把提交状态置为 failure（事务方法，经代理调用）。
     *
     * @param submitRecordId 提交记录主键
     * @throws IllegalStateException 失败记录和提交状态未同步更新（整体回滚）
     */
    @Transactional
    public void registerFailure(Long submitRecordId) {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setSubmitRecordId(submitRecordId);
        failureSubmit.setStatus(FailureSubmitStatus.PENDING.getValue());
        failureSubmit.setRetryCount(0);

        int inserted = failureSubmitMapper.insert(failureSubmit);
        int updated = submitRecordMapper.updateRecordToFailure(submitRecordId);
        if (inserted != 1 || updated != 1) {
            throw new IllegalStateException("失败记录和提交状态未同步更新，submitId=" + submitRecordId);
        }
    }

    /** 日志用的消息体截断，避免超长载荷刷屏。 */
    private String abbreviate(String body) {
        if (body == null || body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200) + "...";
    }
}

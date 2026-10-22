package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.JudgeReturnRecordDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.MQ.MessageAcknowledgements;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.service.FunctionTestCaseGenerationService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class DebugHandle implements MessageHandler {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private FunctionTestCaseGenerationService functionTestCaseGenerationService;

    // Bound pending redelivery frequency without changing queue topology or ACKing unfinished work.
    long pendingBackoffMillis = 1_000;

    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_DEBUG_ROUTING_KEY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(String message, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        final String uuid;
        try {
            uuid = parseTaskId(message);
        } catch (Exception exception) {
            log.error("调试回调任务 ID 非法，deliveryTag={}", deliveryTag, exception);
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }

        String successKey = RedisContext.QUESTION_SUCCESS_KEY + uuid;
        boolean claimed = false;
        boolean completed = false;
        boolean generationTask = false;
        try {
            claimed = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(successKey, "pending", 5, TimeUnit.MINUTES));
            if (!claimed) {
                if ("success".equals(redisTemplate.opsForValue().get(successKey))) {
                    MessageAcknowledgements.ack(channel, deliveryTag);
                } else {
                    // A pending lease is not proof of completion; keep the callback deliverable.
                    backoffPending();
                    MessageAcknowledgements.nack(channel, deliveryTag, true);
                }
                return;
            }

            List<JudgeReturnRecordDto> results = (List<JudgeReturnRecordDto>) redisTemplate.opsForHash()
                    .get(RedisContext.JUDGE_RESULT_KEY, uuid);
            if (results == null || results.isEmpty()) {
                throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE);
            }
            generationTask = functionTestCaseGenerationService.isGenerationTask(uuid);
            if (generationTask) {
                try {
                    functionTestCaseGenerationService.complete(uuid, results);
                } catch (Exception exception) {
                    log.error("随机测试生成失败, taskId={}", uuid, exception);
                    // A persisted FAILED task is a terminal result; never expose dependency text.
                    functionTestCaseGenerationService.fail(uuid, CommonErrorCode.INTERNAL_ERROR.getMessage());
                }
            } else {
                WebsocketSendDto payload = new WebsocketSendDto();
                payload.setQueueName(WebsocketContexts.DEBUG_JUDGE_RESULT);
                payload.setUserId(results.getFirst().getUserId());
                payload.setResult(results);
                rabbitTemplate.convertAndSend(MqContexts.MESSAGE_EXCHANGE, MqContexts.WEBSOCKET_ROUTING_KEY, payload);
            }
            redisTemplate.opsForValue().set(successKey, "success", 5, TimeUnit.MINUTES);
            completed = true;
        } catch (Exception exception) {
            log.error("调试回调处理失败, taskId={}, deliveryTag={}", uuid, deliveryTag, exception);
            if (claimed && !completed) {
                try {
                    redisTemplate.delete(successKey);
                } catch (Exception releaseException) {
                    log.warn("调试回调 pending 释放失败, taskId={}", uuid, releaseException);
                }
            }
            MessageAcknowledgements.nack(channel, deliveryTag, false);
            return;
        }

        // An ACK failure has uncertain broker outcome. Keep success and results for redelivery,
        // and never attempt a contradictory NACK on the same delivery tag.
        if (MessageAcknowledgements.ack(channel, deliveryTag)) {
            cleanupBestEffort(uuid, generationTask);
        }
    }

    private void backoffPending() {
        if (pendingBackoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pendingBackoffMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String parseTaskId(String message) {
        if (message == null || message.isBlank()) {
            throw new BizException(QuestionErrorCode.TASK_ID_INVALID);
        }
        String body = message.trim();
        // A callback can arrive bare, as a JSON string, as an event envelope, or as an
        // envelope/JSON string that a JSON message converter encoded a second time.
        for (int depth = 0; depth < 3; depth++) {
            if (isTaskId(body)) {
                return body;
            }
            if (!body.startsWith("\"") && !body.startsWith("{")) {
                break;
            }
            String unwrapped;
            try {
                unwrapped = EnvelopeCodec.unwrap(body, String.class);
            } catch (IllegalArgumentException exception) {
                throw new BizException(QuestionErrorCode.TASK_ID_INVALID, exception);
            }
            if (unwrapped == null || unwrapped.isBlank() || unwrapped.equals(body)) {
                break;
            }
            body = unwrapped.trim();
        }
        if (isTaskId(body)) {
            return body;
        }
        throw new BizException(QuestionErrorCode.TASK_ID_INVALID);
    }

    private boolean isTaskId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void cleanupBestEffort(String uuid, boolean generationTask) {
        try {
            redisTemplate.opsForHash().delete(RedisContext.JUDGE_RESULT_KEY, uuid);
        } catch (Exception exception) {
            log.warn("已 ACK 调试回调结果清理失败, taskId={}", uuid, exception);
        }
        if (generationTask) {
            try {
                redisTemplate.opsForHash().delete(RedisContext.JUDGE_DEBUG_KEY, uuid);
            } catch (Exception exception) {
                log.warn("已 ACK 生成任务清理失败, taskId={}", uuid, exception);
            }
        }
    }
}

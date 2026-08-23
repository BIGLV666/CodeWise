package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.outbox.OutboxService;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 判题结果回调消费者（question.submit.record.routing）。
 *
 * <p>可靠性纪律：</p>
 * <ul>
 *   <li>CAS 幂等：submit_record 以 judge_status='judging' -&gt; 'success' 条件更新收尾，
 *       仅 CAS 命中的那次消费才执行题目计数与 REVIEW 事件登记，重复投递自然跳过；</li>
 *   <li>ACK 后置：业务在 {@link TransactionTemplate} 事务内提交成功后才 basicAck，
 *       消除「事务内先 ACK、随后回滚」导致的计数丢失；幂等跳过同样 ACK；</li>
 *   <li>Outbox 转发：REVIEW 场景事件经事务性 Outbox 与 CAS/计数同事务提交，
 *       由 Relay 至少一次投递，消费端按 judgeRecordId 幂等；</li>
 *   <li>失败处置：事务回滚后单次 nack（Redis 计数，未超限 requeue 重投，
 *       达到上限留存 failed 记录后丢弃），不再向上抛出，避免外层分发器二次 nack。</li>
 * </ul>
 */
@Component
@Slf4j
public class SubmitRecordHandel implements MessageHandler {

    /**
     * WebSocket 推送大字段截断上限（字符数）。
     *
     * <p>截断只作用于发往 MQ -> WebSocket 的 DTO 副本：数据库写入
     * （submit_record 状态更新）与判题结果比较均使用原始完整数据，
     * 不受截断影响；error 为编译/系统错误诊断信息，不参与截断。</p>
     */
    static final int MAX_FIELD_LENGTH = 16 * 1024;

    /** 截断标记后缀，提示前端内容已裁剪 */
    private static final String TRUNCATED_SUFFIX = "...[truncated]";

    /** 消费失败最大重试次数：达到后留存失败记录并丢弃消息。 */
    static final int MAX_RETRY_COUNT = 3;

    /** 消费失败重试计数 Redis Hash key（field 为消息体 MD5）。 */
    private static final String RETRY_COUNT_KEY = "question:judge:retry-count";

    /** 重试超限消息留存 Redis Hash key（field=消息体 MD5，value=原始消息体）。 */
    private static final String FAILED_KEY = "question:judge:failed";

    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private JudgeRecordMapper judgeRecordMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY;
    }

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        final Long judgeRecordId;
        try {
            // 解析消息：信封 / 裸 Long 双读（灰度期间新旧格式共存）
            judgeRecordId = EnvelopeCodec.unwrap(messageBody, Long.class);
        } catch (IllegalArgumentException e) {
            // 毒消息：重投也无法恢复，直接丢弃
            log.error("判题结果消息载荷解析失败，按毒消息丢弃, deliveryTag: {}", deliveryTag, e);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            log.info("处理判题结果消息, judgeRecordId: {}, deliveryTag: {}", judgeRecordId, deliveryTag);

            JudgeRecord judgeRecord = judgeRecordMapper.selectById(judgeRecordId);

            if (judgeRecord == null || judgeRecord.getSubmitRecordId() == null) {
                log.error("判题结果数据不完整, judgeRecordId: {}", judgeRecordId);
                channel.basicAck(deliveryTag, false);  // 确认并丢弃
                return;
            }
            SubmitRecord submitRecord = submitRecordMapper.selectById(judgeRecord.getSubmitRecordId());
            if (submitRecord == null) {
                log.error("提交记录不存在: submitRecordId: {}", judgeRecord.getSubmitRecordId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            JudgeResultDto judgeResultDto = buildJudgeResult(submitRecord, judgeRecord);

            // 收尾在独立事务内原子提交；execute 返回即已提交，事务内异常会回滚并向上抛
            boolean applied = Boolean.TRUE.equals(transactionTemplate.execute(
                    status -> applyJudgeResult(judgeRecord, submitRecord, judgeResultDto)));

            // ★ ACK 严格在事务提交之后（幂等跳过同样 ACK），只确认一次，不批量确认
            channel.basicAck(deliveryTag, false);

            if (applied) {
                clearRetryCountBestEffort(messageBody);
                // WebSocket 推送在 ACK 之后尽力而为，失败不影响已提交业务
                pushWebsocketBestEffort(submitRecord, judgeResultDto);
                log.info("判题结果处理成功, judgeRecordId: {}, submitRecordId: {}",
                        judgeRecordId, submitRecord.getSubmitRecordId());
            }
        } catch (Exception e) {
            log.error("处理判题结果失败, judgeRecordId: {}", judgeRecordId, e);
            // 单次 nack 处置（Redis 计数重试），不再向上抛出，避免外层分发器二次 nack
            handleFailure(messageBody, deliveryTag, channel, e);
        }
    }

    /**
     * 事务体：CAS 收尾 submit_record，命中后同事务完成题目计数与 REVIEW 事件登记。
     *
     * @return true=本次消费完成收尾；false=CAS 未命中且记录已 success（幂等跳过）
     * @throws IllegalStateException CAS 未命中且状态异常 / 计数更新失败（整体回滚）
     */
    private boolean applyJudgeResult(JudgeRecord judgeRecord, SubmitRecord submitRecord, JudgeResultDto judgeResultDto) {
        Long submitRecordId = submitRecord.getSubmitRecordId();

        // CAS 收尾：仅 judging -> success 命中一次，重复投递在此被挡下
        int updated = submitRecordMapper.updateJudgeSuccess(
                submitRecordId,
                judgeResultDto.getSubmitStatus(),
                judgeResultDto.getTimeUsed(),
                judgeResultDto.getMemoryUsed());
        if (updated == 0) {
            SubmitRecord latest = submitRecordMapper.selectById(submitRecordId);
            if (latest != null && "success".equals(latest.getJudgeStatus())) {
                log.info("重复消费，提交记录已收尾, judgeRecordId: {}, submitRecordId: {}",
                        judgeRecord.getJudgeRecordId(), submitRecordId);
                return false;
            }
            throw new IllegalStateException("提交记录 CAS 收尾未命中且状态非 success, submitRecordId="
                    + submitRecordId + ", judgeStatus="
                    + (latest == null ? "null" : latest.getJudgeStatus()));
        }

        // 题目统计与 CAS 同事务：只有真正收尾的那次消费才计数，失败抛异常整体回滚
        Long questionId = submitRecord.getQuestionId();
        if (questionMapper.updateTotal(questionId) == 0) {
            throw new IllegalStateException("题目提交计数更新失败, questionId=" + questionId);
        }
        if ("AC".equals(judgeResultDto.getSubmitStatus()) && questionMapper.updateTotalAc(questionId) == 0) {
            throw new IllegalStateException("题目 AC 计数更新失败, questionId=" + questionId);
        }

        // REVIEW 场景改走事务性 Outbox：与 CAS/计数同事务提交，由 Relay 至少一次投递
        if ("REVIEW".equals(submitRecord.getSubmitScene())) {
            outboxService.append(
                    EventTypes.REVIEW_JUDGE_RECORD,
                    MqContexts.REVIEW_EXCHANGE,
                    MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY,
                    buildReviewJudgeRecordDto(submitRecord, judgeRecord));
        }
        return true;
    }

    /** 组装判题结果 DTO（读取原始完整数据，不做截断）。 */
    private JudgeResultDto buildJudgeResult(SubmitRecord submitRecord, JudgeRecord judgeRecord) {
        return JudgeResultDto.builder()
                .submissionId(submitRecord.getSubmitRecordId())
                .language(submitRecord.getLanguage())
                .code(submitRecord.getSubmitContent())
                .submitStatus(judgeRecord.getSubmitStatus())
                .failInde(judgeRecord.getFailIndex())
                .expectedOutput(judgeRecord.getExpectedOutput())
                .actual(judgeRecord.getUserOutput())
                .timeUsed(judgeRecord.getTimeUsed())
                .memoryUsed(judgeRecord.getMemoryUsed())
                .error(judgeRecord.getErrorMsg())
                .log(judgeRecord.getLog())
                .build();
    }

    /** 组装 REVIEW 场景转发 DTO（judgeRecordId 为消费端幂等键，必须有值）。 */
    private ReviewJudgeRecordDto buildReviewJudgeRecordDto(SubmitRecord submitRecord, JudgeRecord judgeRecord) {
        ReviewJudgeRecordDto reviewJudgeRecordDto = new ReviewJudgeRecordDto();
        reviewJudgeRecordDto.setUserId(submitRecord.getUserId());
        reviewJudgeRecordDto.setQuestionId(submitRecord.getQuestionId());
        reviewJudgeRecordDto.setSubmitRecordId(submitRecord.getSubmitRecordId());
        reviewJudgeRecordDto.setJudgeRecordId(judgeRecord.getJudgeRecordId());
        reviewJudgeRecordDto.setStatus(judgeRecord.getSubmitStatus());
        reviewJudgeRecordDto.setErrorMessage(judgeRecord.getErrorMsg());
        reviewJudgeRecordDto.setAllTestTotal(judgeRecord.getTestTotal());
        reviewJudgeRecordDto.setAcTestTotal(judgeRecord.getFailIndex() - 1);
        reviewJudgeRecordDto.setQuestionTitle(submitRecord.getQuestionTitle());
        return reviewJudgeRecordDto;
    }

    /**
     * 消费成功后清理重试计数（尽力而为）：避免同一消息体残留历史计数，
     * 导致后续偶发重投被提前判定超限。
     */
    private void clearRetryCountBestEffort(String messageBody) {
        try {
            redisTemplate.opsForHash().delete(RETRY_COUNT_KEY, md5MessageKey(messageBody));
        } catch (Exception e) {
            log.warn("重试计数清理失败（业务已提交，忽略）", e);
        }
    }

    /**
     * 事务失败后的单次 nack 处置：Redis HINCRBY 按消息体 MD5 计数，
     * 未超限 requeue 重投；达到上限留存 failed 记录后丢弃（requeue=false）。
     * Redis 访问异常按未超限处理（宁可多重试，不误丢弃）。
     */
    private void handleFailure(String messageBody, long deliveryTag, Channel channel, Exception cause)
            throws IOException {
        Long retryCount = null;
        try {
            retryCount = redisTemplate.opsForHash().increment(RETRY_COUNT_KEY, md5MessageKey(messageBody), 1);
        } catch (Exception redisException) {
            log.warn("重试计数写入 Redis 失败，按可重试处理", redisException);
        }
        if (retryCount != null && retryCount >= MAX_RETRY_COUNT) {
            log.error("判题结果消费失败重试超限（{} 次），留存失败记录并丢弃, messageKey: {}",
                    retryCount, md5MessageKey(messageBody), cause);
            try {
                redisTemplate.opsForHash().put(FAILED_KEY, md5MessageKey(messageBody), messageBody);
            } catch (Exception redisException) {
                log.warn("失败消息留存 Redis 异常", redisException);
            }
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        log.warn("判题结果消费失败，第 {} 次重试将重新入队", retryCount, cause);
        channel.basicNack(deliveryTag, false, true);
    }

    /** 消息体 MD5，作为 Redis Hash field 的稳定消息键。 */
    private static String md5MessageKey(String messageBody) {
        return DigestUtils.md5DigestAsHex(messageBody.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * ACK 之后的 WebSocket 推送（尽力而为）：失败只告警，不影响已提交的业务与 ACK。
     */
    private void pushWebsocketBestEffort(SubmitRecord submitRecord, JudgeResultDto judgeResultDto) {
        try {
            WebsocketSendDto websocketSendDto = new WebsocketSendDto();
            websocketSendDto.setQueueName(WebsocketContexts.JUDGE_RESULT);
            websocketSendDto.setUserId(submitRecord.getUserId());
            // 推送给前端的结果使用截断副本，避免超大 code/log/输出撑爆 WS 消息
            websocketSendDto.setResult(truncateForPush(judgeResultDto));
            rabbitTemplate.convertAndSend(
                    MqContexts.MESSAGE_EXCHANGE,
                    MqContexts.WEBSOCKET_ROUTING_KEY,
                    websocketSendDto
            );
        } catch (Exception e) {
            log.warn("判题结果 WebSocket 推送失败（业务已提交）, submitRecordId: {}",
                    submitRecord.getSubmitRecordId(), e);
        }
    }

    /**
     * 构建发往 WebSocket 的判题结果副本，对 code/log/expectedOutput/actual
     * 四个大字段做长度上限截断（error 不截）。
     *
     * <p>边界说明：截断只影响本方法返回的 MQ 推送副本；数据库写入
     * （submit_record 状态/耗时/内存更新）与判题比较发生在截断之前，
     * 始终使用 judgeRecord/submitRecord 的原始完整数据。</p>
     */
    private JudgeResultDto truncateForPush(JudgeResultDto source) {
        return JudgeResultDto.builder()
                .submissionId(source.getSubmissionId())
                .language(source.getLanguage())
                .code(truncate(source.getCode()))
                .submitStatus(source.getSubmitStatus())
                .failInde(source.getFailInde())
                .expectedOutput(truncate(source.getExpectedOutput()))
                .actual(truncate(source.getActual()))
                .timeUsed(source.getTimeUsed())
                .memoryUsed(source.getMemoryUsed())
                .error(source.getError())
                .log(truncate(source.getLog()))
                .build();
    }

    /**
     * 单字段截断：超出上限时截断并附加标记后缀（总长不超过上限）。
     */
    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_FIELD_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FIELD_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }
}

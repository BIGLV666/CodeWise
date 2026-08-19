package org.example.servicejudge.Mq.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 判题死信处理器。
 * 将进入死信队列的提交登记到 failure_submit，并把提交状态置为 failure，
 * 后续由管理员接口或补偿流程投递重试消息。
 */
@Slf4j
@Service
public class JudgeDeadLetterHandler {

    private final SubmitRecordMapper submitRecordMapper;
    private final FailureSubmitMapper failureSubmitMapper;

    public JudgeDeadLetterHandler(
            SubmitRecordMapper submitRecordMapper,
            FailureSubmitMapper failureSubmitMapper
    ) {
        this.submitRecordMapper = submitRecordMapper;
        this.failureSubmitMapper = failureSubmitMapper;
    }

    /**
     * 登记进入判题死信队列的提交。
     *
     * @param submitRecordId 提交记录主键
     * @param deathInfo RabbitMQ x-death 元数据
     */
    @RabbitListener(queues = MqContexts.JUDGE_DLQ)
    @Transactional
    public void consumeDeadMessage(
            Long submitRecordId,
            @Header(required = false, name = "x-death") Object deathInfo
    ) {
        FailureSubmit failureSubmit = new FailureSubmit();
        failureSubmit.setSubmitRecordId(submitRecordId);
        failureSubmit.setStatus(FailureSubmitStatus.PENDING.getValue());
        failureSubmit.setRetryCount(0);

        try {
            int inserted = failureSubmitMapper.insert(failureSubmit);
            int updated = submitRecordMapper.updateRecordToFailure(submitRecordId);
            if (inserted != 1 || updated != 1) {
                throw new IllegalStateException("失败记录和提交状态未同步更新，submitId=" + submitRecordId);
            }
        } catch (DuplicateKeyException exception) {
            log.info("死信消息重复消费，submitId={}", submitRecordId);
            throw exception;
        }

        log.error("判题消息进入死信队列，submitId={}, death={}", submitRecordId, deathInfo);
    }
}

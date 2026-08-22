package org.example.servicejudge.Mq.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.event.EventPublisher;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.JudgeRecordMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.example.servicejudge.service.JudgeTaskService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 判题失败重试消息处理器。
 *
 * <p>处理流程：原子抢占失败记录 -> 校验提交状态 -> 复用已有结果或经
 * {@link JudgeTaskService} 重新判题 -> 标记重试成功。该流程只回调题目服务，
 * 不发送 AI 建议事件（维持现状）。</p>
 *
 * <p>本类不再承担 Channel/ACK 职责：幂等分支正常返回由消费者 ACK；
 * 失败时先落库 failure_submit=FAILURE 再上抛异常，由消费者 nack 进死信留痕。</p>
 */
@Service
@Slf4j
public class JudgeRetryHandler {

    private static final int MAX_LAST_ERROR_LENGTH = 4000;

    private final FailureSubmitMapper failureSubmitMapper;
    private final SubmitRecordMapper submitRecordMapper;
    private final JudgeRecordMapper judgeRecordMapper;
    private final EventPublisher eventPublisher;
    private final JudgeTaskService judgeTaskService;

    public JudgeRetryHandler(
            FailureSubmitMapper failureSubmitMapper,
            SubmitRecordMapper submitRecordMapper,
            JudgeRecordMapper judgeRecordMapper,
            EventPublisher eventPublisher,
            JudgeTaskService judgeTaskService
    ) {
        this.failureSubmitMapper = failureSubmitMapper;
        this.submitRecordMapper = submitRecordMapper;
        this.judgeRecordMapper = judgeRecordMapper;
        this.eventPublisher = eventPublisher;
        this.judgeTaskService = judgeTaskService;
    }

    /**
     * 消费重试消息并完成一次失败提交的补偿判题。
     *
     * @param failureId failure_submit.failure_submit_id
     * @throws IOException           判题执行失败（已先登记 failure_submit=FAILURE）
     * @throws IllegalStateException 记录抢占后查询为空、提交/题目缺失、状态异常等
     *                               （已先登记 failure_submit=FAILURE）
     */
    public void handle(Long failureId) throws IOException {
        if (failureSubmitMapper.updateStatusToRetry(failureId) != 1) {
            // 未找到记录、已被抢占或已处理：幂等分支，正常返回交由消费者 ACK
            log.info("未找到该记录、已被其他消费者抢占或已处理，failureId={}", failureId);
            return;
        }

        try {
            FailureSubmit failureSubmit = failureSubmitMapper.selectById(failureId);
            if (failureSubmit == null) {
                throw new IllegalStateException("重试记录抢占后查询为空，failureId=" + failureId);
            }

            SubmitRecord submitRecord = submitRecordMapper.selectById(failureSubmit.getSubmitRecordId());
            if (submitRecord == null) {
                throw new IllegalStateException("提交记录不存在，submitId=" + failureSubmit.getSubmitRecordId());
            }
            Long submissionId = submitRecord.getSubmitRecordId();

            if ("success".equals(submitRecord.getJudgeStatus())) {
                markRetrySuccess(failureId);
                log.info("提交已经处理完成，submitId={}", submissionId);
                return;
            }

            JudgeRecord existingResult = findExistingJudgeResult(submissionId);
            if (existingResult != null) {
                publishQuestionResult(existingResult);
                markRetrySuccess(failureId);
                log.info("复用已有判题结果并重新回调，submitId={}, judgeRecordId={}",
                        submissionId, existingResult.getJudgeRecordId());
                return;
            }

            if (submitRecordMapper.updateRecordToJudge(submissionId) != 1) {
                throw new IllegalStateException(
                        "提交记录状态不是 pending，无法开始重试，submitId=" + submissionId
                                + ", judgeStatus=" + submitRecord.getJudgeStatus());
            }

            // 判题 + 落库 + Outbox 结果回调事件（sendAiAdvice=false：重试流程不发 AI 建议）
            judgeTaskService.judgeAndPersist(submitRecord, false);

            markRetrySuccess(failureId);
        } catch (Exception e) {
            // 先登记失败状态再上抛：消费者据此 nack 进死信，failure_submit 已留痕
            log.error("判题重试处理失败，failureId={}", failureId, e);
            String lastError = buildLastError(e);
            int updated = failureSubmitMapper.updateFailureStatus(
                    failureId,
                    FailureSubmitStatus.FAILURE.getValue(),
                    lastError);
            if (updated != 1) {
                log.warn("记录失败状态或 lastError 未更新，failureId={}", failureId);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("判题重试处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询同一提交已有的最新判题结果，避免消息重投造成重复执行和重复插入。
     */
    private JudgeRecord findExistingJudgeResult(Long submissionId) {
        return judgeRecordMapper.selectOne(
                new QueryWrapper<JudgeRecord>()
                        .eq("submit_record_id", submissionId)
                        .orderByDesc("judge_record_id")
                        .last("LIMIT 1"));
    }

    /**
     * 向题目服务发送判题结果回调（非事务直发；重试流程不发送 AI 建议消息）。
     */
    private void publishQuestionResult(JudgeRecord judgeRecord) {
        if (judgeRecord == null || judgeRecord.getJudgeRecordId() == null) {
            throw new IllegalStateException("判题结果为空或未生成判题记录");
        }
        eventPublisher.publish(
                MqContexts.Question_EXCHANGE,
                MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY,
                EventTypes.JUDGE_RESULT_CALLBACK,
                judgeRecord.getJudgeRecordId());
    }

    /**
     * 将失败记录标记为成功，并清理历史错误信息。
     */
    private void markRetrySuccess(Long failureId) {
        if (failureSubmitMapper.updateStatus(failureId, FailureSubmitStatus.SUCCESS.getValue()) != 1) {
            throw new IllegalStateException("修改失败提交状态失败，failureId=" + failureId);
        }
    }

    /**
     * 将异常转换为可持久化的错误摘要，避免 TEXT 字段被超长堆栈撑爆。
     */
    private String buildLastError(Exception exception) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        String error = writer.toString();
        if (error.length() <= MAX_LAST_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_LAST_ERROR_LENGTH);
    }
}

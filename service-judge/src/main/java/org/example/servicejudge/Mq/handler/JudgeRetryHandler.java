package org.example.servicejudge.Mq.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Mq.MessageHandler;
import org.example.servicejudge.Util.CodeBuild;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.entry.FunctionConfig;
import org.example.servicejudge.entry.FunctionTestCase;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.Question;
import org.example.servicejudge.entry.SubmitRecord;
import org.example.servicejudge.entry.TestCase;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.functionsService.Java;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.example.servicejudge.mapper.FunctionConfigMapper;
import org.example.servicejudge.mapper.FunctionTestCaseMapper;
import org.example.servicejudge.mapper.JudgeRecordMapper;
import org.example.servicejudge.mapper.QuestionMapper;
import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.example.servicejudge.mapper.TestCaseMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 判题失败重试消息处理器。
 *
 * <p>处理流程：原子抢占失败记录 -> 校验提交状态 -> 复用已有结果或重新判题
 * -> 写入判题结果 -> 回调题目服务 -> ACK。该流程只发送题目服务回调，不发送 AI 建议。</p>
 */
@Service
@Slf4j
public class JudgeRetryHandler implements MessageHandler {

    private static final int MAX_LAST_ERROR_LENGTH = 4000;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JudgeInterface judge;
    @Autowired
    private FailureSubmitMapper failureSubmitMapper;
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private JudgeRecordMapper judgeRecordMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private FunctionConfigMapper functionConfigMapper;
    @Autowired
    private FunctionTestCaseMapper functionTestCaseMapper;
    @Autowired
    private TestCaseMapper testCaseMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public String getRoutingKey() {
        return MqContexts.JUDGE_RETRY_ROUTING_KEY;
    }

    /**
     * 消费重试消息并完成一次失败提交的补偿判题。
     *
     * @param message     failure_submit.failure_submit_id 的 JSON 数字值
     * @param channel     当前 RabbitMQ channel
     * @param amqpMessage 当前消息，用于获取 delivery tag
     */
    @Override
    @Transactional
    public void handle(String message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        Long failureId = objectMapper.readValue(message, Long.class);

        try {
            if (failureSubmitMapper.updateStatusToRetry(failureId) != 1) {
                log.info("未找到该记录、已被其他消费者抢占或已处理，failureId={}", failureId);
                acknowledge(channel, deliveryTag, failureId);
                return;
            }

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
                acknowledge(channel, deliveryTag, failureId);
                return;
            }

            JudgeRecord existingResult = findExistingJudgeResult(submissionId);
            if (existingResult != null) {
                publishQuestionResult(existingResult);
                markRetrySuccess(failureId);
                log.info("复用已有判题结果并重新回调，submitId={}, judgeRecordId={}",
                        submissionId, existingResult.getJudgeRecordId());
                acknowledge(channel, deliveryTag, failureId);
                return;
            }

            if (submitRecordMapper.updateRecordToJudge(submissionId) != 1) {
                throw new IllegalStateException(
                        "提交记录状态不是 pending，无法开始重试，submitId=" + submissionId
                                + ", judgeStatus=" + submitRecord.getJudgeStatus());
            }

            Question question = questionMapper.selectById(submitRecord.getQuestionId());
            if (question == null || question.getQuestionType() == null) {
                throw new IllegalStateException("题目或题目类型不存在，questionId=" + submitRecord.getQuestionId());
            }

            JudgeRecord finalResult;
            if (QuestionType.ACM.equals(question.getQuestionType())) {
                finalResult = judgeAcm(submitRecord);
            } else if (QuestionType.FUNCTION.equals(question.getQuestionType())) {
                finalResult = judgeFunction(submitRecord);
            } else {
                throw new IllegalStateException("不支持的题目类型：" + question.getQuestionType());
            }

            publishQuestionResult(finalResult);
            markRetrySuccess(failureId);
            acknowledge(channel, deliveryTag, failureId);
        } catch (Exception e) {
            log.error("判题重试处理失败，failureId={}", failureId, e);
            String lastError = buildLastError(e);
            int updated = failureSubmitMapper.updateFailureStatus(
                    failureId,
                    FailureSubmitStatus.FAILURE.getValue(),
                    lastError);
            if (updated != 1) {
                log.warn("记录失败状态或 lastError 未更新，failureId={}", failureId);
            }
            reject(channel, deliveryTag, failureId);
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
     * 向题目服务发送判题结果回调；重试流程不发送 AI 建议消息。
     */
    private void publishQuestionResult(JudgeRecord judgeRecord) {
        if (judgeRecord == null || judgeRecord.getJudgeRecordId() == null) {
            throw new IllegalStateException("判题结果为空或未生成判题记录");
        }
        // 重试流程只回调题目服务，不推送 AI 建议。
        rabbitTemplate.convertAndSend(
                MqContexts.Question_EXCHANGE,
                MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY,
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

    private void acknowledge(Channel channel, long deliveryTag, Long failureId) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            // 数据库和回调已经完成时，不再把业务状态改回失败；消息重投后会被幂等分支确认。
            log.error("ACK失败，等待 RabbitMQ 重新投递，failureId={}", failureId, e);
        }
    }

    private void reject(Channel channel, long deliveryTag, Long failureId) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            log.error("NACK失败，failureId={}", failureId, e);
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

    private List<TestDto> toTestDtos(List<TestCase> testCases) {
        List<TestDto> testDtos = new ArrayList<>();
        for (TestCase testCase : testCases) {
            testDtos.add(new TestDto(testCase));
        }
        return testDtos;
    }

    private List<TestDto> toFunctionTestDtos(List<FunctionTestCase> testCases) {
        List<TestDto> testDtos = new ArrayList<>();
        for (FunctionTestCase testCase : testCases) {
            testDtos.add(new TestDto(testCase));
        }
        return testDtos;
    }

    private JudgeRecord judgeAcm(SubmitRecord submitRecord) throws IOException {
        List<TestCase> testCases = testCaseMapper.selectList(
                new QueryWrapper<TestCase>().eq("question_id", submitRecord.getQuestionId()));

        JudgeRecord finalResult = judge.batchExecuteCode(
                submitRecord.getSubmitContent(),
                submitRecord.getLanguage(),
                toTestDtos(testCases));
        prepareAndInsertResult(finalResult, submitRecord, testCases.size());
        return finalResult;
    }

    private JudgeRecord judgeFunction(SubmitRecord submitRecord) throws IOException {
        List<FunctionTestCase> testCases = functionTestCaseMapper.selectList(
                new QueryWrapper<FunctionTestCase>().eq("question_id", submitRecord.getQuestionId()));

        FunctionConfig functionConfig = functionConfigMapper.selectOne(
                new QueryWrapper<FunctionConfig>().eq("question_id", submitRecord.getQuestionId()));
        if (functionConfig == null) {
            throw new InterruptedIOException("函数模式题目配置不存在");
        }
        if (!"java".equalsIgnoreCase(submitRecord.getLanguage())) {
            throw new InterruptedIOException("函数模式暂时只支持 Java");
        }

        String main = Java.ToMain(
                functionConfig.getParameterConfig(),
                functionConfig.getMethodName(),
                functionConfig.getOutputType());
        String code = CodeBuild.build(
                submitRecord.getSubmitContent(),
                functionConfig.getParameterConfig(),
                functionConfig.getOutputType());

        JudgeRecord finalResult = judge.batchExecuteCode(
                code,
                main,
                submitRecord.getLanguage(),
                toFunctionTestDtos(testCases));
        prepareAndInsertResult(finalResult, submitRecord, testCases.size());
        return finalResult;
    }

    /**
     * 补齐提交关联字段并持久化判题结果，同时校验主键是否回填。
     */
    private void prepareAndInsertResult(JudgeRecord finalResult, SubmitRecord submitRecord, int totalCount) {
        if (finalResult == null) {
            throw new IllegalStateException("判题器返回空结果，submitId=" + submitRecord.getSubmitRecordId());
        }
        finalResult.setSubmitRecordId(submitRecord.getSubmitRecordId());
        finalResult.setCode(submitRecord.getSubmitContent());
        finalResult.setCreateTime(LocalDateTime.now());
        finalResult.setTestTotal(totalCount);

        int inserted = judgeRecordMapper.insert(finalResult);
        if (inserted != 1 || finalResult.getJudgeRecordId() == null) {
            throw new IllegalStateException("判题结果入库失败，submitId=" + submitRecord.getSubmitRecordId());
        }
    }
}

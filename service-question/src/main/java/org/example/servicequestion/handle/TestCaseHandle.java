package org.example.servicequestion.handle;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.TestMessage;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AI 测试用例生成结果消费者（question.testcace.routing）。
 *
 * <p>可靠性纪律（与 {@link SubmitRecordHandel} 一致）：</p>
 * <ul>
 *   <li>ACK 后置：去重插入与 AI 状态收尾在 {@link TransactionTemplate} 事务内
 *       原子提交，成功返回后才 basicAck，消除「先 ACK 后回滚」；</li>
 *   <li>失败处置：事务回滚后单次 nack（Redis 计数，未超限 requeue 重投，
 *       达到上限留存 failed 记录后丢弃），不再向上抛出，避免外层分发器二次 nack。</li>
 * </ul>
 *
 * <p>本消费者不向其他服务转发消息，无需 Outbox。</p>
 */
@Component
@Slf4j
public class TestCaseHandle implements MessageHandler {

    /** 消费失败最大重试次数：达到后留存失败记录并丢弃消息。 */
    static final int MAX_RETRY_COUNT = 3;

    /** 消费失败重试计数 Redis Hash key（field 为消息体 MD5）。 */
    private static final String RETRY_COUNT_KEY = "question:testcase:retry-count";

    /** 重试超限消息留存 Redis Hash key（field=消息体 MD5，value=原始消息体）。 */
    private static final String FAILED_KEY = "question:testcase:failed";

    @Autowired
    private TestCaseMapper testCaseMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public String getRoutingKey() {
        return MqContexts.Question_TESTCASE_ROUTING_KEY;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(String messageBody, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            // 解析消息
            List<TestMessage> messages = objectMapper.readValue(messageBody, new TypeReference<List<TestMessage>>() {});
            log.info("解析到 {} 个测试用例, questionId: {}, deliveryTag: {}",
                    messages.size(),
                    messages.isEmpty() ? null : messages.getFirst().getQuestionId(),
                    deliveryTag);

            if (messages.isEmpty()) {
                log.warn("没有解析到任何测试用例");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 业务在独立事务内原子提交；execute 返回即已提交，事务内异常会回滚并向上抛
            transactionTemplate.executeWithoutResult(status -> applyTestCases(messages));

            // ★ ACK 严格在事务提交之后，只确认一次，不批量确认
            channel.basicAck(deliveryTag, false);
            log.info("测试用例消息处理完成, questionId: {}", messages.getFirst().getQuestionId());
        } catch (Exception e) {
            log.error("处理测试用例消息失败, deliveryTag: {}", deliveryTag, e);
            // 单次 nack 处置（Redis 计数重试），不再向上抛出，避免外层分发器二次 nack
            handleFailure(messageBody, deliveryTag, channel, e);
        }
    }

    /**
     * 事务体：按输入+期望输出的 MD5 去重插入测试用例，并把题目 AI 状态收尾为 success，
     * 同事务原子提交（修复原先先 ACK 再改状态导致的顺序问题）。
     */
    private void applyTestCases(List<TestMessage> messages) {
        Set<String> testset = new HashSet<>();
        List<TestCase> testcases = testCaseMapper.selectList(
                new QueryWrapper<TestCase>().eq("question_id", messages.getFirst().getQuestionId()));
        for (TestCase testcase : testcases) {
            testset.add(testCaseFingerprint(testcase.getInputData(), testcase.getExpectedOutput()));
        }

        int successCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            TestMessage testMessage = messages.get(i);

            TestCase testCase = new TestCase();
            testCase.setQuestionId(testMessage.getQuestionId());
            testCase.setInputData(testMessage.getInputData());
            testCase.setExpectedOutput(testMessage.getExpectedOutput());
            testCase.setTimeLimit(testMessage.getTimeLimit());
            testCase.setMemoryLimit(testMessage.getMemoryLimit());
            testCase.setCreateUserId(testMessage.getCreateUserId());
            testCase.setScoreWeight(testMessage.getScoreWeight());
            testCase.setSortOrder(testMessage.getSortOrder());
            testCase.setCreateTime(LocalDateTime.now());
            testCase.setUpdateTime(LocalDateTime.now());

            int result = 0;
            if (!testset.contains(testCaseFingerprint(testCase.getInputData(), testCase.getExpectedOutput()))) {
                result = testCaseMapper.insert(testCase);
                testset.add(testCaseFingerprint(testCase.getInputData(), testCase.getExpectedOutput()));
            }
            if (result > 0) {
                successCount++;
            } else {
                log.warn("测试用例未插入（重复或失败）, questionId: {}, 序号: {}",
                        testCase.getQuestionId(), i + 1);
            }
        }

        log.info("成功插入 {} 个测试用例, questionId: {}", successCount, messages.getFirst().getQuestionId());

        // AI 状态收尾与插入同事务：整体成功才置 success
        questionMapper.updateAiStatusToSuccess(messages.getFirst().getQuestionId());
    }

    /** 测试用例指纹：输入+期望输出拼接的 MD5，用于批内与库内去重。 */
    private static String testCaseFingerprint(String inputData, String expectedOutput) {
        return DigestUtils.md5DigestAsHex(
                (Arrays.toString(inputData.getBytes()) + expectedOutput).getBytes());
    }

    /**
     * 事务失败后的单次 nack 处置：Redis HINCRBY 按消息体 MD5 计数，
     * 未超限 requeue 重投；达到上限留存 failed 记录后丢弃（requeue=false）。
     * Redis 访问异常按未超限处理（宁可多重试，不误丢弃）。
     */
    private void handleFailure(String messageBody, long deliveryTag, Channel channel, Exception cause)
            throws IOException {
        String messageKey = md5MessageKey(messageBody);
        Long retryCount = null;
        try {
            retryCount = redisTemplate.opsForHash().increment(RETRY_COUNT_KEY, messageKey, 1);
        } catch (Exception redisException) {
            log.warn("重试计数写入 Redis 失败，按可重试处理", redisException);
        }
        if (retryCount != null && retryCount >= MAX_RETRY_COUNT) {
            log.error("测试用例消息消费失败重试超限（{} 次），留存失败记录并丢弃, messageKey: {}",
                    retryCount, messageKey, cause);
            try {
                redisTemplate.opsForHash().put(FAILED_KEY, messageKey, messageBody);
            } catch (Exception redisException) {
                log.warn("失败消息留存 Redis 异常", redisException);
            }
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        log.warn("测试用例消息消费失败，第 {} 次重试将重新入队", retryCount, cause);
        channel.basicNack(deliveryTag, false, true);
    }

    /** 消息体 MD5，作为 Redis Hash field 的稳定消息键。 */
    private static String md5MessageKey(String messageBody) {
        return DigestUtils.md5DigestAsHex(messageBody.getBytes(StandardCharsets.UTF_8));
    }
}

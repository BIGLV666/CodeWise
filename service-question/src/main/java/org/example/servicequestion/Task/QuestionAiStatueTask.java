package org.example.servicequestion.Task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.config.MqContexts;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.example.servicequestion.until.ToDto;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.example.servicequestion.until.ToDto.ToQuestionMessage;

@Slf4j
@Async
@Component
public class QuestionAiStatueTask {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TestCaseMapper testCaseMapper;

    // 超时时间（分钟）
    private static final int TIMEOUT_MINUTES = 2;

    // 最小测试用例数量
    private static final int MIN_TEST_CASES = 5;

    @Scheduled(cron = "0 */5 * * * *")
    public void aiStatue() {
        log.info("========== 开始扫描AI状态 ==========");

        try {
            // 1. 查询需要处理的题目（pending 或 failure）
            List<Question> questions = questionMapper.selectList(
                    new QueryWrapper<Question>()
                            .eq("ai_statue", "pending")
                            .or()
                            .eq("ai_statue", "failure")

            );

            if (questions == null || questions.isEmpty()) {
                log.info("没有需要处理的题目");
                return;
            }

            log.info("查询到 {} 条待处理记录", questions.size());

            // 2. 收集需要处理的题目和 ID
            List<Question> needRetry = new ArrayList<>();
            List<Long> needDeleteIds = new ArrayList<>();
            List<Question> needSuccess = new ArrayList<>();

            for (Question question : questions) {
                String status = question.getAiStatue();
                Long questionId = question.getQuestionId();

                // 获取该题目的测试用例数量
                long testCaseCount = testCaseMapper.selectCount(
                        new QueryWrapper<TestCase>().eq("question_id", questionId)
                );

                // 判断是否应该标记为 success
                if (testCaseCount >= MIN_TEST_CASES) {
                    // 已有足够的测试用例，标记为成功
                    question.setAiStatue("success");
                    needSuccess.add(question);
                    log.info("题目 {} 已有 {} 个测试用例，标记为 success", questionId, testCaseCount);
                    continue;
                }

                // 判断是否需要重试
                boolean shouldRetry = false;

                if ("failure".equals(status)) {
                    // failure 状态且测试用例 > 0 且 < 5，需要重试
                    if (testCaseCount >= 0) {
                        shouldRetry = true;
                    }
                } else if ("pending".equals(status)) {
                    // pending 状态且超时，且有测试用例，需要重试
                    if (testCaseCount >=0  && isTimeout(question.getCreateTime())) {
                        shouldRetry = true;
                    }
                }

                if (shouldRetry) {
                    needRetry.add(question);
                    needDeleteIds.add(questionId);
                    log.info("题目 {} 需要重试，当前状态: {}, 测试用例数: {}",
                            questionId, status, testCaseCount);
                }
            }

            // 3. 批量删除需要重试的题目的测试用例
            //if (!needDeleteIds.isEmpty()) {
                //long deleted = testCaseMapper.deleteByQuestionIds(needDeleteIds);
               /// log.info("删除了 {} 个题目的测试用例，共 {} 条", needDeleteIds.size(), deleted);
           // }

            // 4. 更新成功状态的题目到数据库
            if (!needSuccess.isEmpty()) {
                for (Question question : needSuccess) {
                    questionMapper.updateById(question);
                }
                log.info("更新了 {} 个题目为 success", needSuccess.size());
            }

            // 5. 重新发送需要重试的题目到 AI 队列
            if (!needRetry.isEmpty()) {
                for (Question question : needRetry) {
                    // 重置状态为 pending
                    question.setAiStatue("pending");
                    questionMapper.updateById(question);

                    // 发送到 AI 队列
                    rabbitTemplate.convertAndSend(
                            MqContexts.Ai_EXCHANGE,
                            MqContexts.Ai_TESTCASE_ROUTING_KEY,
                            ToQuestionMessage(question)
                    );
                    log.info("重新发送题目 {} 到 AI 队列", question.getQuestionId());
                }
                log.info("共重新发送 {} 个题目到 AI 队列", needRetry.size());
            }

            log.info("========== 扫描完成 ==========");
            log.info("成功: {}, 重试: {}, 删除: {}", needSuccess.size(), needRetry.size(), needDeleteIds.size());

        } catch (Exception e) {
            log.error("扫描AI状态失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断是否超时
     */
    private boolean isTimeout(LocalDateTime createTime) {
        if (createTime == null) {
            return true;
        }
        return Duration.between(createTime, LocalDateTime.now()).toMinutes() > TIMEOUT_MINUTES;
    }
}
package org.example.servicejudge.Mq.handler;

import lombok.extern.slf4j.Slf4j;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.service.JudgeTaskService;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 正常判题消息处理器：消费初次提交，委托 {@link JudgeTaskService} 完成
 * 「领取 -> 判题 -> 落库 -> 结果/AI 建议事件」。
 *
 * <p>本类不再承担 Channel/ACK 职责：幂等分支（记录不存在/已处理/被抢占）
 * 正常返回由消费者 ACK；业务失败以异常上抛，由消费者决定延迟重试或死信。</p>
 */
@Service
@Slf4j
public class JudgeSubmitHandler {

    private final JudgeTaskService judgeTaskService;

    public JudgeSubmitHandler(JudgeTaskService judgeTaskService) {
        this.judgeTaskService = judgeTaskService;
    }

    /**
     * 处理一次提交判题。
     *
     * @param submissionId 提交记录主键
     * @throws IOException           判题执行失败（进入延迟重试链路）
     * @throws IllegalStateException 题目/配置缺失、结果入库失败等业务异常
     */
    public void handle(Long submissionId) throws IOException {
        JudgeRecord finalResult = judgeTaskService.handleSubmit(submissionId);
        if (finalResult == null) {
            log.info("提交已由其他消费者处理或不存在，正常确认: submitId={}", submissionId);
        }
    }
}

package org.example.servicejudge.task;

import lombok.extern.slf4j.Slf4j;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失败判题记录的兜底补偿任务。
 * 每分钟修正超过五分钟仍停留在重试中或失败状态的记录。
 */
@Component
@Slf4j
public class FailureSubmitReconcileTask {

    private final FailureSubmitMapper failureSubmitMapper;

    public FailureSubmitReconcileTask(FailureSubmitMapper failureSubmitMapper) {
        this.failureSubmitMapper = failureSubmitMapper;
    }

    /**
     * 定时补偿任务：每分钟执行一次
     *
     * 处理超过 5 分钟仍处于重试中或失败状态的记录：
     * - 有判题结果：标记为成功
     * - 无判题结果：重新进入待处理状态
     */
    @Scheduled(cron = "0 * * * * *")
    public void reconcileFailureSubmits() {
        try {
            int affectedRows = failureSubmitMapper.reconcileStaleSubmits();
            if (affectedRows > 0) {
                log.info("失败提交补偿完成，影响记录数：{}", affectedRows);
            }
        } catch (Exception e) {
            log.error("失败提交补偿任务执行异常", e);
        }
    }
}

package org.example.servicejudge.service.failure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.servicecommon.config.MqContexts;
import org.example.servicejudge.entry.FailureSubmit;
import org.example.servicejudge.enums.FailureSubmitStatus;
import org.example.servicejudge.mapper.FailureSubmitMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 失败提交管理服务。
 * 负责管理员查询失败记录和发起人工重试，不直接执行判题。
 */
@Service
public class FailureSubmitService {

    private static final Set<Integer> SUPPORTED_STATUSES = Set.of(0, 1, 2, 3);

    private final FailureSubmitMapper failureSubmitMapper;
    private final RabbitTemplate rabbitTemplate;

    public FailureSubmitService(
            FailureSubmitMapper failureSubmitMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.failureSubmitMapper = failureSubmitMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 将一条待处理失败记录投递到判题重试 routing key。
     * 真正的状态抢占、幂等判断和判题执行由 JudgeRetryHandler 完成。
     *
     * @param failureId failure_submit 主键
     */
    public void retry(Long failureId) {
        FailureSubmit failureSubmit = failureSubmitMapper.selectById(failureId);
        if (failureSubmit == null) {
            throw new IllegalArgumentException("未找到该任务");
        }
        if (!Objects.equals(failureSubmit.getStatus(), FailureSubmitStatus.PENDING.getValue())) {
            throw new IllegalArgumentException("当前任务不是待重试状态");
        }

        rabbitTemplate.convertAndSend(
                MqContexts.JUDGE_EXCHANGE,
                MqContexts.JUDGE_RETRY_ROUTING_KEY,
                failureId);
    }

    /**
     * 按失败记录状态查询管理列表。
     *
     * @param status FailureSubmitStatus 对应数值
     * @return 指定状态的失败提交记录
     */
    public List<FailureSubmit> getRecordsByStatus(Integer status) {
        if (status == null) {
            throw new IllegalArgumentException("状态不能为空");
        }
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法状态");
        }
        return failureSubmitMapper.selectList(
                new QueryWrapper<FailureSubmit>().eq("status", status));
    }
}

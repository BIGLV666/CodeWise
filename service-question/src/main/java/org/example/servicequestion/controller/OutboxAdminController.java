package org.example.servicequestion.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.outbox.OutboxEvent;
import org.example.servicecommon.outbox.mapper.OutboxMapper;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.service.QuestionPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Outbox 事件管理端点（仅管理员）。
 *
 * <p>Outbox 事件投递失败超过重试上限后会转为 DEAD 状态并停止自动投递，
 * 本端点提供 DEAD 事件的查询与人工重放（DEAD -&gt; PENDING）能力，
 * 供运维在修复 broker/消费端问题后手动补发。</p>
 *
 * <p>鉴权：复用 {@link QuestionPermissionService#requireAdmin(Long)}
 * 的管理员校验（roleId==2），非管理员请求直接拒绝。</p>
 */
@RestController
@RequestMapping("/api/question/outbox")
public class OutboxAdminController {

    /** 单次最多返回的 DEAD 事件条数 */
    private static final int DEAD_QUERY_LIMIT = 200;

    /** OutboxMapper 由 service-common 的 OutboxAutoConfiguration 自动注册 */
    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private QuestionPermissionService questionPermissionService;

    /**
     * 查询处于 DEAD 状态的 Outbox 事件（按 outboxId 倒序，最多 200 条）。
     *
     * @return DEAD 事件列表
     */
    @GetMapping("/dead")
    public Result<List<OutboxEvent>> listDead() {
        questionPermissionService.requireAdmin(UserContext.getUserId());
        List<OutboxEvent> deadEvents = outboxMapper.selectList(
                new QueryWrapper<OutboxEvent>()
                        .eq("status", OutboxEvent.STATUS_DEAD)
                        .orderByDesc("outbox_id")
                        .last("LIMIT " + DEAD_QUERY_LIMIT)
        );
        return Result.success(deadEvents);
    }

    /**
     * 人工重放一条 DEAD 事件：将其重置为 PENDING 并清零重试计数，
     * 随后由 Relay 按常规节奏重新投递。
     *
     * @param outboxId 事件主键
     * @return 重放成功返回 success；事件不存在或不是 DEAD 状态时返回错误
     */
    @PostMapping("/replay/{outboxId}")
    public Result<Void> replayDead(@PathVariable("outboxId") Long outboxId) {
        questionPermissionService.requireAdmin(UserContext.getUserId());
        int updated = outboxMapper.replayDead(outboxId);
        if (updated == 0) {
            return Result.error("事件不存在或不是 DEAD 状态");
        }
        return Result.success(null, "重放已入队");
    }
}

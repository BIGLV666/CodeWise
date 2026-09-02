package org.example.servicecommon.outboxpro;

import org.outboxpro.spi.deadletter.DlqReplayAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OutboxPro 运维端点的最小授权边界：只授予只读检索 scope。
 *
 * <p>OutboxPro 的 ops/replay 端点在无授权 bean 时默认全拒。本实现放行
 * {@code outbox:list} 与 {@code dlq:list}（死信/出箱记录的只读查询），
 * 拒绝 {@code outbox:replay}——人工重放暂不开放（配置层
 * {@code outboxpro.dlq.replay.enabled} 默认也是关的，此为第二道防线）。</p>
 *
 * <p>端点本身还受 {@code UserAuthInterceptor} 的内部 Token 校验保护
 * （/actuator/** 不在探针白名单内），双重约束下仅内网持 Token 方可查询。</p>
 */
public class CodewiseDlqReadAuthorizer implements DlqReplayAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(CodewiseDlqReadAuthorizer.class);

    @Override
    public void authorize(String eventIdOrScope, String operator) {
        if ("outbox:list".equals(eventIdOrScope) || "dlq:list".equals(eventIdOrScope)) {
            return;
        }
        log.warn("拒绝 OutboxPro 运维操作: scope/eventId={}, operator={}（仅只读检索开放，重放未启用）",
                eventIdOrScope, operator);
        throw new SecurityException("CodeWise 仅开放 outbox:list / dlq:list 只读检索；人工重放请走 DBA 流程");
    }
}

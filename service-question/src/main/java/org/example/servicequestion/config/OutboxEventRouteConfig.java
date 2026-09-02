package org.example.servicequestion.config;

import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.outboxpro.core.event.EventDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * service-question 的事件路由登记：事件类型 -> 载荷类型 -> RabbitMQ 路由。
 *
 * <p>OutboxPro 经 EventRegistry 解析 {@code publisher.publish(eventType, payload)}
 * 的目标路由；载荷 DTO 保持不加注解，service-api 不引入 outboxpro 依赖。</p>
 *
 * <p>约定：与业务数据原子一致的事件走 OutboxPro（本类登记）；
 * 事务外的尽力而为发送（如 WebSocket 推送）仍用 EventPublisher/RabbitTemplate 直发。</p>
 */
@Configuration
public class OutboxEventRouteConfig {

    /** 提交判题请求：question -> judge，与 submit_record 插入同事务 */
    @Bean
    public EventDefinition<Long> judgeSubmitRequest() {
        return EventDefinition.<Long>builder()
                .eventType(EventTypes.JUDGE_SUBMIT_REQUEST)
                .payloadType(Long.class)
                .route(MqContexts.JUDGE_EXCHANGE, MqContexts.JUDGE_ROUTING_KEY)
                .build();
    }

    /** 调试判题请求：question -> judge，与 Redis 任务写入对应的 Outbox 登记同事务 */
    @Bean
    public EventDefinition<String> judgeDebugRequest() {
        return EventDefinition.<String>builder()
                .eventType(EventTypes.JUDGE_DEBUG_REQUEST)
                .payloadType(String.class)
                .route(MqContexts.JUDGE_EXCHANGE, MqContexts.JUDGE_DEBUG_ROUTING_KEY)
                .build();
    }

    /** 复习场景判题结果：question -> review，与 submit_record CAS 收尾/题目计数同事务 */
    @Bean
    public EventDefinition<ReviewJudgeRecordDto> reviewJudgeRecord() {
        return EventDefinition.<ReviewJudgeRecordDto>builder()
                .eventType(EventTypes.REVIEW_JUDGE_RECORD)
                .payloadType(ReviewJudgeRecordDto.class)
                .route(MqContexts.REVIEW_EXCHANGE, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY)
                .build();
    }

    /** AI 用例生成请求：question -> ai，与题目导入插入同事务（原为事务内裸发） */
    @Bean
    public EventDefinition<QuestionMessage> aiTestcaseRequest() {
        return EventDefinition.<QuestionMessage>builder()
                .eventType(EventTypes.AI_TESTCASE_REQUEST)
                .payloadType(QuestionMessage.class)
                .route(MqContexts.Ai_EXCHANGE, MqContexts.Ai_TESTCASE_ROUTING_KEY)
                .build();
    }
}

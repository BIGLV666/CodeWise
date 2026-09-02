package org.example.servicejudge.config;

import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.outboxpro.core.event.EventDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * service-judge 的事件路由登记：事件类型 -> 载荷类型 -> RabbitMQ 路由。
 *
 * <p>OutboxPro 经 EventRegistry 解析 {@code publisher.publish(eventType, payload)}
 * 的目标路由；载荷 DTO 保持不加注解，service-api 不引入 outboxpro 依赖。</p>
 */
@Configuration
public class OutboxEventRouteConfig {

    /** 判题结果回调：judge -> question，与 judge_record 插入同事务（payload 为 judgeRecordId） */
    @Bean
    public EventDefinition<Long> judgeResultCallback() {
        return EventDefinition.<Long>builder()
                .eventType(EventTypes.JUDGE_RESULT_CALLBACK)
                .payloadType(Long.class)
                .route(MqContexts.Question_EXCHANGE, MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY)
                .build();
    }

    /** AI 判题建议请求：judge -> ai，与 judge_record 插入同事务（payload 为瘦身引用 DTO） */
    @Bean
    public EventDefinition<AiAdviceWADto> aiAdviceRequest() {
        return EventDefinition.<AiAdviceWADto>builder()
                .eventType(EventTypes.AI_ADVICE_REQUEST)
                .payloadType(AiAdviceWADto.class)
                .route(MqContexts.Ai_EXCHANGE, MqContexts.AI_WA_ADVICE_ROUTING_KEY)
                .build();
    }
}

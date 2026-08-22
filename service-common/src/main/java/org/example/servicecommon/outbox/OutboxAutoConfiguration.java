package org.example.servicecommon.outbox;

import org.example.servicecommon.event.EventPublisher;
import org.example.servicecommon.outbox.mapper.OutboxMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Outbox 基础设施自动装配。
 *
 * <p>开关 {@code codewise.outbox.enabled=true} 时生效：
 * 注册 OutboxMapper 扫描、事务内写入服务 {@link OutboxService}，
 * 并在 {@code codewise.outbox.relay.enabled}（默认 true）时启动
 * 定时投递器 {@link OutboxRelay}。Relay 依赖调度线程，
 * 启用方需保证应用已开启 @EnableScheduling。</p>
 *
 * <p>question 与 judge 共用 codewise_question 库的同一张 event_outbox 表，
 * 两个服务都启用 Relay 是安全的：认领使用 FOR UPDATE SKIP LOCKED。</p>
 *
 * <p>注意 {@code @AutoConfigureAfter(RabbitAutoConfiguration)}：自定义自动配置
 * 默认按 FQN 排在 Spring 官方之前解析，缺省顺序下 Relay 上的
 * {@code @ConditionalOnBean(RabbitTemplate)} 永远为 false，事件会滞留 PENDING。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.baomidou.mybatisplus.core.mapper.BaseMapper")
@ConditionalOnProperty(name = "codewise.outbox.enabled", havingValue = "true")
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "org.example.servicecommon.event.EventAutoConfiguration"
})
@MapperScan(basePackageClasses = OutboxMapper.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxService outboxService(
            OutboxMapper outboxMapper,
            @Value("${spring.application.name:unknown}") String applicationName) {
        return new OutboxService(outboxMapper, applicationName);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RabbitTemplate.class)
    @ConditionalOnProperty(name = "codewise.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(OutboxMapper outboxMapper, EventPublisher eventPublisher) {
        return new OutboxRelay(outboxMapper, eventPublisher);
    }
}

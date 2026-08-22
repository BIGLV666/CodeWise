package org.example.servicecommon.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 事件发布基础设施自动装配。
 *
 * <p>类路径存在 RabbitTemplate 且容器已装配 RabbitTemplate 时注册
 * {@link EventPublisher}；service-name 缺省为 unknown，仅影响信封
 * producer 字段的展示。</p>
 *
 * <p>注意必须 {@code @AutoConfigureAfter(RabbitAutoConfiguration)}：
 * 自定义自动配置（org.example.*）默认按 FQN 排在 Spring 官方自动配置之前解析，
 * 若不显式声明顺序，方法级/类级 {@code @ConditionalOnBean(RabbitTemplate)}
 * 在评估时永远为 false，EventPublisher 不会被注册。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnBean(RabbitTemplate.class)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration")
public class EventAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher eventPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${spring.application.name:unknown}") String applicationName) {
        return new EventPublisher(rabbitTemplate, applicationName);
    }
}
